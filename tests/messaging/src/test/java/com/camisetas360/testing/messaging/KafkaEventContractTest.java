package com.camisetas360.testing.messaging;

import com.camisetas360.analytics.messaging.EventoParser;
import com.camisetas360.recommendation.messaging.InteraccionEventoParser;
import com.camisetas360.recommendation.model.Accion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contratos de los eventos Kafka (docs/contracts/analytics-events.md y pago-events.md).
 *
 * Los DTOs estan duplicados en cada servicio (no hay modulo compartido). Aqui se usan las clases
 * REALES de cada productor (publishers de catalog y carrito, PagoEvento + ObjectMapper de pagos)
 * y los parsers REALES de cada consumidor (analytics EventoParser, recommendation
 * InteraccionEventoParser). Un campo renombrado en un lado hace fallar CI en vez de terminar
 * como "evento descartado" en el log de produccion.
 */
class KafkaEventContractTest {

    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-AAAA-2222-2222-222222222222";
    private static final Instant AHORA = Instant.parse("2026-11-13T10:15:30.123Z");

    private final EventoParser analytics = new EventoParser();
    private final InteraccionEventoParser recommendation = new InteraccionEventoParser();

    // CT-KAFKA-001
    @Test
    void interaccionEventoSchema_isIdenticalInEveryProducerAndConsumer() {
        var referencia = schema(com.camisetas360.catalog.messaging.event.InteraccionEvento.class);
        for (Class<?> copia : new Class<?>[] {
                com.camisetas360.carrito.messaging.event.InteraccionEvento.class,
                com.camisetas360.analytics.model.InteraccionEvento.class,
                com.camisetas360.recommendation.messaging.InteraccionEvento.class}) {
            assertThat(schema(copia)).as(copia.getName()).isEqualTo(referencia);
        }
        assertThat(com.camisetas360.catalog.messaging.event.InteraccionEvento.VERSION)
                .isEqualTo(com.camisetas360.carrito.messaging.event.InteraccionEvento.VERSION)
                .isEqualTo(com.camisetas360.analytics.model.InteraccionEvento.VERSION)
                .isEqualTo(com.camisetas360.recommendation.messaging.InteraccionEvento.VERSION);
    }

    // CT-KAFKA-002
    @Test
    void topicNames_matchBetweenProducersAndConsumers() {
        assertThat(com.camisetas360.catalog.messaging.KafkaTopics.PRODUCTO_VISTO)
                .isEqualTo(com.camisetas360.analytics.messaging.KafkaTopics.PRODUCTO_VISTO)
                .isEqualTo(com.camisetas360.recommendation.messaging.KafkaTopics.PRODUCTO_VISTO);
        assertThat(com.camisetas360.carrito.messaging.KafkaTopics.ITEM_AGREGADO)
                .isEqualTo(com.camisetas360.analytics.messaging.KafkaTopics.ITEM_AGREGADO)
                .isEqualTo(com.camisetas360.recommendation.messaging.KafkaTopics.ITEM_AGREGADO);
        assertThat(com.camisetas360.carrito.messaging.KafkaTopics.ITEM_ELIMINADO)
                .isEqualTo(com.camisetas360.analytics.messaging.KafkaTopics.ITEM_ELIMINADO);
        assertThat(com.camisetas360.carrito.messaging.KafkaTopics.ENVIO_ACTUALIZADO)
                .isEqualTo(com.camisetas360.analytics.messaging.KafkaTopics.ENVIO_ACTUALIZADO);
        assertThat(com.camisetas360.pago.messaging.PagoTopicos.FRAUDE_DETECTADO)
                .isEqualTo(com.camisetas360.analytics.messaging.KafkaTopics.PAGO_FRAUDE_DETECTADO);
    }

    // CT-KAFKA-003
    @Test
    void catalogProductoVisto_isAcceptedByAnalyticsAndRecommendation() {
        var template = template();
        new com.camisetas360.catalog.messaging.InteraccionEventPublisher(template, true).publicar(
                com.camisetas360.catalog.messaging.KafkaTopics.PRODUCTO_VISTO,
                new com.camisetas360.catalog.messaging.event.InteraccionEvento(
                        UUID.randomUUID(), "PRODUCTO_VISTO",
                        com.camisetas360.catalog.messaging.event.InteraccionEvento.VERSION,
                        AHORA.toString(), "catalog", USUARIO,
                        new com.camisetas360.catalog.messaging.event.InteraccionEvento.ProductoSnapshot(
                                "CHI-LOC-2026", "Camiseta Chile Local 2026", "Selecciones", 29990.0),
                        null, null));
        var enviado = enviado(template, com.camisetas360.catalog.messaging.KafkaTopics.PRODUCTO_VISTO);
        assertThat(enviado.getKey()).as("clave de particion = usuario").isEqualTo(USUARIO);

        var enAnalytics = analytics.interaccion(enviado.getValue()).orElseThrow(
                () -> new AssertionError("analytics descarto PRODUCTO_VISTO de catalog: " + enviado.getValue()));
        assertThat(enAnalytics.producto().sku()).isEqualTo("CHI-LOC-2026");
        assertThat(enAnalytics.producto().categoria()).isEqualTo("Selecciones");
        assertThat(enAnalytics.producto().precio()).isEqualTo(29990.0);

        var enRecomendacion = recommendation.parse(enviado.getValue()).orElseThrow(
                () -> new AssertionError("recommendation descarto PRODUCTO_VISTO de catalog: " + enviado.getValue()));
        assertThat(enRecomendacion.accion()).isEqualTo(Accion.VISTA);
        assertThat(enRecomendacion.usuarioId()).isEqualTo(USUARIO.toLowerCase());
        assertThat(enRecomendacion.ocurridoEn()).isEqualTo(AHORA);
    }

    // CT-KAFKA-004
    @Test
    void carritoEvents_areAcceptedByTheirConsumers() {
        var producto = new com.camisetas360.carrito.messaging.event.InteraccionEvento.ProductoSnapshot(
                "ARG-VIS-2026", "Camiseta Argentina Visita", "Selecciones", 34990.0);

        var agregado = publicarCarrito(com.camisetas360.carrito.messaging.KafkaTopics.ITEM_AGREGADO,
                "ITEM_AGREGADO", producto, 2, null);
        assertThat(analytics.interaccion(agregado)).as("analytics ITEM_AGREGADO").isPresent()
                .get().extracting(e -> e.cantidad()).isEqualTo(2);
        var carrito = recommendation.parse(agregado).orElseThrow(
                () -> new AssertionError("recommendation descarto ITEM_AGREGADO de carrito: " + agregado));
        assertThat(carrito.accion()).isEqualTo(Accion.CARRITO);
        assertThat(carrito.producto().sku()).isEqualTo("ARG-VIS-2026");

        var eliminado = publicarCarrito(com.camisetas360.carrito.messaging.KafkaTopics.ITEM_ELIMINADO,
                "ITEM_ELIMINADO", producto, 1, null);
        assertThat(analytics.interaccion(eliminado)).as("analytics ITEM_ELIMINADO").isPresent();

        var envio = publicarCarrito(com.camisetas360.carrito.messaging.KafkaTopics.ENVIO_ACTUALIZADO,
                "ENVIO_ACTUALIZADO", null, null,
                new com.camisetas360.carrito.messaging.event.InteraccionEvento.EnvioSnapshot("EXPRESS", "Metropolitana"));
        assertThat(analytics.interaccion(envio)).as("analytics ENVIO_ACTUALIZADO").isPresent()
                .get().extracting(e -> e.envio().metodo()).isEqualTo("EXPRESS");
    }

    // CT-KAFKA-005
    @Test
    void pagoFraudeDetectado_fromPagosObjectMapper_isAcceptedByAnalytics() throws Exception {
        var ordenId = UUID.randomUUID();
        var evento = com.camisetas360.pago.messaging.PagoEvento.crear("PAGO_FRAUDE_DETECTADO", ordenId,
                UUID.randomUUID(), new BigDecimal("39990.50"), "BLOQUEADO_FRAUDE",
                "a".repeat(64), "9012", "4 intentos en 60 s");
        // Mismo ObjectMapper que usa OutboxService para guardar el payload que OutboxRelay envia tal cual.
        String payload = new com.camisetas360.pago.config.JacksonConfig().objectMapper().writeValueAsString(evento);

        var alerta = analytics.fraude(payload).orElseThrow(
                () -> new AssertionError("analytics descarto PAGO_FRAUDE_DETECTADO de pagos: " + payload));
        assertThat(alerta.ordenId()).isEqualTo(ordenId.toString());
        assertThat(alerta.monto()).isEqualTo(39990.50);
        assertThat(alerta.ultimos4()).isEqualTo("9012");
        assertThat(alerta.motivo()).isEqualTo("4 intentos en 60 s");
        assertThat(payload).as("timestamp ISO-8601, no epoch").contains("\"timestamp\":\"");
    }

    private String publicarCarrito(String topic, String tipo,
                                   com.camisetas360.carrito.messaging.event.InteraccionEvento.ProductoSnapshot producto,
                                   Integer cantidad,
                                   com.camisetas360.carrito.messaging.event.InteraccionEvento.EnvioSnapshot envio) {
        var template = template();
        new com.camisetas360.carrito.messaging.InteraccionEventPublisher(template, true).publicar(topic,
                new com.camisetas360.carrito.messaging.event.InteraccionEvento(UUID.randomUUID(), tipo,
                        com.camisetas360.carrito.messaging.event.InteraccionEvento.VERSION, AHORA.toString(),
                        "carrito", USUARIO, producto, cantidad, envio));
        var enviado = enviado(template, topic);
        assertThat(enviado.getKey()).isEqualTo(USUARIO);
        return enviado.getValue();
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> template() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<SendResult<String, String>>());
        return template;
    }

    private static Map.Entry<String, String> enviado(KafkaTemplate<String, String> template, String topic) {
        var key = ArgumentCaptor.forClass(String.class);
        var value = ArgumentCaptor.forClass(String.class);
        verify(template).send(org.mockito.ArgumentMatchers.eq(topic), key.capture(), value.capture());
        return Map.entry(key.getValue(), value.getValue());
    }

    /** Nombre y tipo logico de cada componente, recursivo en records anidados. */
    private static Map<String, String> schema(Class<?> record) {
        var schema = new TreeMap<String, String>();
        for (var component : record.getRecordComponents()) {
            schema.put(component.getName(), logicalType(component.getGenericType()));
            if (component.getType().isRecord()) {
                schema(component.getType()).forEach((k, v) -> schema.put(component.getName() + "." + k, v));
            }
        }
        return schema;
    }

    private static String logicalType(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return logicalType(parameterized.getRawType()) + "<"
                    + Arrays.stream(parameterized.getActualTypeArguments()).map(KafkaEventContractTest::logicalType)
                    .collect(Collectors.joining(",")) + ">";
        }
        if (type instanceof Class<?> clazz) {
            return clazz.isRecord() ? clazz.getSimpleName() : clazz.getName();
        }
        return type.getTypeName();
    }
}
