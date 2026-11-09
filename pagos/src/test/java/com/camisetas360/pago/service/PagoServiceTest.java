package com.camisetas360.pago.service;

import com.camisetas360.pago.dto.IniciarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoResponse;
import com.camisetas360.pago.dto.PagoResponseDTO;
import com.camisetas360.pago.exception.FraudeDetectadoException;
import com.camisetas360.pago.messaging.PagoTopicos;
import com.camisetas360.pago.model.AlertaFraude;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.model.Pago;
import com.camisetas360.pago.port.*;
import com.camisetas360.pago.repository.AlertaFraudeRepository;
import com.camisetas360.pago.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PagoServiceTest {

    @Mock
    private PagoRepository pagoRepository;

    @Mock
    private AlertaFraudeRepository alertaFraudeRepository;

    @Mock
    private FraudeDetector fraudeDetector;

    @Mock
    private PasarelaPagoPort pasarelaPagoPort;

    @Mock
    private OutboxService outboxService;

    private PagoService pagoService;

    private static final String SECRETO = "test-secreto-antifraude";

    @BeforeEach
    void setUp() {
        pagoService = new PagoService(
                pagoRepository,
                alertaFraudeRepository,
                fraudeDetector,
                pasarelaPagoPort,
                outboxService,
                SECRETO
        );
    }

    @Test
    @DisplayName("iniciarPago exitoso cuando la regla antifraude permite la transacción")
    void iniciarPagoExitoso() {
        UUID ordenId = UUID.randomUUID();
        IniciarPagoRequest request = new IniciarPagoRequest(
                ordenId,
                BigDecimal.valueOf(25000),
                "4532123456789012",
                "123",
                12,
                2026,
                "Juan Perez"
        );

        when(fraudeDetector.evaluarTransaccion(anyString(), eq(ordenId)))
                .thenReturn(new ResultadoEvaluacionFraude(true, 1, 10, "OK"));

        when(pasarelaPagoPort.iniciarTransaccion(any(IniciarPagoComando.class)))
                .thenReturn(new IniciarPagoResult("tok_12345", "http://localhost:8087/retorno?token_ws=tok_12345"));

        when(pagoRepository.save(any(Pago.class))).thenAnswer(invocation -> {
            Pago p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        IniciarPagoResponse response = pagoService.iniciarPago(request);

        assertNotNull(response);
        assertEquals(ordenId, response.ordenId());
        assertEquals("INICIADO", response.estado());
        assertEquals("tok_12345", response.token());
        assertEquals("9012", response.tarjetaUltimos4());
        assertEquals("VISA", response.tarjetaMarca());

        verify(outboxService).registrarEvento(eq(PagoTopicos.INTENTO_REGISTRADO), eq(ordenId.toString()), any());
        verify(alertaFraudeRepository, never()).save(any());
    }

    @Test
    @DisplayName("iniciarPago lanza FraudeDetectadoException y registra alerta cuando se excede el umbral")
    void iniciarPagoBloqueadoPorFraude() {
        UUID ordenId = UUID.randomUUID();
        IniciarPagoRequest request = new IniciarPagoRequest(
                ordenId,
                BigDecimal.valueOf(25000),
                "4532123456789012",
                "123",
                12,
                2026,
                "Juan Perez"
        );

        when(fraudeDetector.evaluarTransaccion(anyString(), eq(ordenId)))
                .thenReturn(new ResultadoEvaluacionFraude(false, 4, 10, "Bloqueado preventivamente por antifraude"));

        when(pagoRepository.save(any(Pago.class))).thenAnswer(invocation -> {
            Pago p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        FraudeDetectadoException ex = assertThrows(FraudeDetectadoException.class, () -> pagoService.iniciarPago(request));

        assertEquals(ordenId, ex.getOrdenId());
        assertEquals(4, ex.getIntentos());
        assertEquals(10, ex.getVentanaSegundos());
        assertEquals("9012", ex.getTarjetaUltimos4());

        // Verificar que se guardó la alerta y el evento en Outbox
        verify(alertaFraudeRepository).save(any(AlertaFraude.class));
        verify(outboxService).registrarEvento(eq(PagoTopicos.FRAUDE_DETECTADO), eq(ordenId.toString()), any());
        verify(pasarelaPagoPort, never()).iniciarTransaccion(any());
    }

    @Test
    @DisplayName("confirmarPago exitoso cuando la pasarela autoriza")
    void confirmarPagoAutorizado() {
        String token = "tok_abc123";
        UUID ordenId = UUID.randomUUID();
        Pago pago = new Pago();
        pago.setId(UUID.randomUUID());
        pago.setOrdenId(ordenId);
        pago.setMonto(BigDecimal.valueOf(19990));
        pago.setEstado(EstadoPago.INICIADO);
        pago.setTokenTransaccion(token);
        pago.setTarjetaUltimos4("9012");
        pago.setTarjetaHash("hash-dummy");
        pago.setTarjetaMarca("VISA");

        when(pagoRepository.findByTokenTransaccion(token)).thenReturn(Optional.of(pago));
        when(pasarelaPagoPort.confirmarTransaccion(token, "9012"))
                .thenReturn(new ConfirmarPagoResult(true, "AUTH-12345678", "0", "Aprobada"));
        when(pagoRepository.save(any(Pago.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PagoResponseDTO response = pagoService.confirmarPago(token);

        assertEquals(EstadoPago.AUTORIZADO, response.estado());
        assertEquals("AUTH-12345678", response.codigoAutorizacion());

        verify(outboxService).registrarEvento(eq(PagoTopicos.AUTORIZADO), eq(ordenId.toString()), any());
    }

    @Test
    @DisplayName("confirmarPago rechazado cuando la pasarela rechaza determinísticamente")
    void confirmarPagoRechazado() {
        String token = "tok_reject0";
        UUID ordenId = UUID.randomUUID();
        Pago pago = new Pago();
        pago.setId(UUID.randomUUID());
        pago.setOrdenId(ordenId);
        pago.setMonto(BigDecimal.valueOf(19990));
        pago.setEstado(EstadoPago.INICIADO);
        pago.setTokenTransaccion(token);
        pago.setTarjetaUltimos4("9000"); // termina en 0
        pago.setTarjetaHash("hash-dummy");
        pago.setTarjetaMarca("VISA");

        when(pagoRepository.findByTokenTransaccion(token)).thenReturn(Optional.of(pago));
        when(pasarelaPagoPort.confirmarTransaccion(token, "9000"))
                .thenReturn(new ConfirmarPagoResult(false, null, "-1", "Fondos insuficientes"));
        when(pagoRepository.save(any(Pago.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PagoResponseDTO response = pagoService.confirmarPago(token);

        assertEquals(EstadoPago.RECHAZADO, response.estado());
        assertNull(response.codigoAutorizacion());
        assertTrue(response.mensajeRespuesta().contains("Fondos insuficientes"));

        verify(outboxService).registrarEvento(eq(PagoTopicos.RECHAZADO), eq(ordenId.toString()), any());
    }
}
