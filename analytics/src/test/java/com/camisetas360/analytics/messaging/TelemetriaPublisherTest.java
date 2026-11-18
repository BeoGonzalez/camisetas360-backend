package com.camisetas360.analytics.messaging;

import com.camisetas360.analytics.model.ProductoTelemetria;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelemetriaPublisherTest {

    private static final ProductoTelemetria PRODUCTO = new ProductoTelemetria(
            "SA-INT-LOC-2627", "Inter Local 26/27", "Fútbol", 94990.0, 5, 2, "2026-11-18T03:00:00Z");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    @Test
    void publicaElEstadoDelProductoConElSkuComoClave() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        new TelemetriaPublisher(kafka, true).publicar(PRODUCTO);

        verify(kafka).send(eq(KafkaTopics.TELEMETRIA_PRODUCTOS), eq("SA-INT-LOC-2627"), argThat(json ->
                json.contains("\"sku\":\"SA-INT-LOC-2627\"") && json.contains("\"vistas\":5")
                        && json.contains("\"agregados\":2") && json.contains("\"actualizadoEn\":\"2026-11-18T03:00:00Z\"")));
    }

    @Test
    void unFalloDeKafkaNoSePropaga() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker caido")));
        assertThatCode(() -> new TelemetriaPublisher(kafka, true).publicar(PRODUCTO)).doesNotThrowAnyException();

        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("timeout metadata"));
        assertThatCode(() -> new TelemetriaPublisher(kafka, true).publicar(PRODUCTO)).doesNotThrowAnyException();
    }

    @Test
    void deshabilitadoOSinProducto_noPublica() {
        new TelemetriaPublisher(kafka, false).publicar(PRODUCTO);
        new TelemetriaPublisher(kafka, true).publicar(null);
        verifyNoInteractions(kafka);
    }
}
