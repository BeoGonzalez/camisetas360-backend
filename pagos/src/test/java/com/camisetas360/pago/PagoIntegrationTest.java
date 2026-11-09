package com.camisetas360.pago;

import com.camisetas360.pago.dto.IniciarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoResponse;
import com.camisetas360.pago.dto.PagoResponseDTO;
import com.camisetas360.pago.exception.FraudeDetectadoException;
import com.camisetas360.pago.messaging.OutboxRelay;
import com.camisetas360.pago.model.AlertaFraude;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.model.OutboxEvento;
import com.camisetas360.pago.model.Pago;
import com.camisetas360.pago.port.FraudeDetector;
import com.camisetas360.pago.repository.AlertaFraudeRepository;
import com.camisetas360.pago.repository.OutboxEventoRepository;
import com.camisetas360.pago.repository.PagoRepository;
import com.camisetas360.pago.service.PagoService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PagoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("camisetas360_pago")
            .withUsername("pago")
            .withPassword("pago_test_pass");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("antifraude.max-intentos", () -> 3);
        registry.add("antifraude.ventana-segundos", () -> 10);
    }

    @Autowired
    private PagoService pagoService;

    @Autowired
    private PagoRepository pagoRepository;

    @Autowired
    private AlertaFraudeRepository alertaFraudeRepository;

    @Autowired
    private OutboxEventoRepository outboxEventoRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private FraudeDetector fraudeDetector;

    @BeforeEach
    void setUp() {
        fraudeDetector.reset();
    }

    @Test
    @DisplayName("Flujo completo de pago: Iniciar -> Outbox -> Confirmar Webpay -> Kafka")
    void flujoCompletoPagoExitoso() {
        UUID ordenId = UUID.randomUUID();
        IniciarPagoRequest request = new IniciarPagoRequest(
                ordenId,
                BigDecimal.valueOf(29990),
                "4532123456789012",
                "987",
                11,
                2027,
                "Usuario Test Integration"
        );

        // 1. Iniciar pago
        IniciarPagoResponse inicio = pagoService.iniciarPago(request);
        assertNotNull(inicio.token());
        assertEquals("INICIADO", inicio.estado());

        // Verificar persistencia en base de datos PostgreSQL
        Pago pagoPersistido = pagoRepository.findById(inicio.pagoId()).orElseThrow();
        assertEquals(EstadoPago.INICIADO, pagoPersistido.getEstado());
        assertEquals(ordenId, pagoPersistido.getOrdenId());
        assertEquals("9012", pagoPersistido.getTarjetaUltimos4());

        // Verificar evento en Outbox
        List<OutboxEvento> outboxPendientes = outboxEventoRepository.findTop50ByEstadoOrderByCreatedAtAsc("PENDIENTE");
        assertFalse(outboxPendientes.isEmpty(), "Debe existir al menos un evento outbox pendiente");

        // Publicar eventos pendientes a Kafka mediante el Relay
        outboxRelay.procesarEventosPendientes();

        // 2. Confirmar transacción
        PagoResponseDTO confirmacion = pagoService.confirmarPago(inicio.token());
        assertEquals(EstadoPago.AUTORIZADO, confirmacion.estado());
        assertNotNull(confirmacion.codigoAutorizacion());

        // Verificar que el estado cambió en PostgreSQL
        Pago pagoFinal = pagoRepository.findById(inicio.pagoId()).orElseThrow();
        assertEquals(EstadoPago.AUTORIZADO, pagoFinal.getEstado());

        // Procesar nuevos eventos outbox a Kafka
        outboxRelay.procesarEventosPendientes();
    }

    @Test
    @DisplayName("Regla Antifraude: 3 intentos pasan, el 4.º se bloquea, persiste alerta y evento outbox")
    void reglaAntifraudeBloqueaCuartoIntento() {
        String tarjeta = "4532999988887777";
        UUID orden1 = UUID.randomUUID();
        UUID orden2 = UUID.randomUUID();
        UUID orden3 = UUID.randomUUID();
        UUID orden4 = UUID.randomUUID();

        // Intentos 1, 2 y 3 deben pasar sin problemas
        IniciarPagoResponse r1 = pagoService.iniciarPago(new IniciarPagoRequest(
                orden1, BigDecimal.valueOf(10000), tarjeta, "123", 12, 2026, "Tester"));
        assertEquals("INICIADO", r1.estado());

        IniciarPagoResponse r2 = pagoService.iniciarPago(new IniciarPagoRequest(
                orden2, BigDecimal.valueOf(10000), tarjeta, "123", 12, 2026, "Tester"));
        assertEquals("INICIADO", r2.estado());

        IniciarPagoResponse r3 = pagoService.iniciarPago(new IniciarPagoRequest(
                orden3, BigDecimal.valueOf(10000), tarjeta, "123", 12, 2026, "Tester"));
        assertEquals("INICIADO", r3.estado());

        // 4.º intento con la misma tarjeta debe ser BLOQUEADO preventivamente
        FraudeDetectadoException ex = assertThrows(FraudeDetectadoException.class, () ->
                pagoService.iniciarPago(new IniciarPagoRequest(
                        orden4, BigDecimal.valueOf(10000), tarjeta, "123", 12, 2026, "Tester"))
        );

        assertEquals(orden4, ex.getOrdenId());
        assertEquals(4, ex.getIntentos());
        assertEquals("7777", ex.getTarjetaUltimos4());

        // Verificar que la alerta se persistió en la tabla alerta_fraude de PostgreSQL
        List<AlertaFraude> alertas = alertaFraudeRepository.findByTarjetaHashOrderByCreatedAtDesc(ex.getTarjetaHash());
        assertFalse(alertas.isEmpty(), "Debe existir registro en alerta_fraude");
        assertEquals(4, alertas.get(0).getIntentosDetectados());
        assertEquals(10, alertas.get(0).getVentanaSegundos());

        // Verificar que el pago fue guardado en estado BLOQUEADO_FRAUDE
        Pago pagoBloqueado = pagoRepository.findById(ex.getPagoId()).orElseThrow();
        assertEquals(EstadoPago.BLOQUEADO_FRAUDE, pagoBloqueado.getEstado());

        // Procesar relay outbox para asegurar que el evento fraude-detectado se envía a Kafka
        outboxRelay.procesarEventosPendientes();
    }
}
