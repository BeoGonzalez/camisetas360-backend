package com.camisetas360.catalog.messaging;

import com.camisetas360.catalog.messaging.event.InteraccionEvento;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InteraccionEventPublisherTest {

    private static final InteraccionEvento EVENTO = new InteraccionEvento(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "PRODUCTO_VISTO", 1,
            "2026-11-10T12:00:00Z", "catalog", "tenant:object",
            new InteraccionEvento.ProductoSnapshot("SKU-A", "Camiseta", "Liga A", 19.5), null, null);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

    @Test
    void publicar_shouldSendJsonKeyedByUser() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(enviado()));

        new InteraccionEventPublisher(template, true).publicar(KafkaTopics.PRODUCTO_VISTO, EVENTO);

        var payload = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("catalogo.producto-visto"), eq("tenant:object"), payload.capture());
        var json = JsonMapper.builder().build().readTree(payload.getValue());
        assertThat(json.get("eventType").asString()).isEqualTo("PRODUCTO_VISTO");
        assertThat(json.get("producto").get("sku").asString()).isEqualTo("SKU-A");
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-11-10T12:00:00Z");
    }

    @Test
    void publicar_shouldNotPropagateBrokerFailures() {
        when(template.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> new InteraccionEventPublisher(template, true)
                .publicar(KafkaTopics.PRODUCTO_VISTO, EVENTO)).doesNotThrowAnyException();
    }

    @Test
    void publicar_shouldNotPropagateAsyncFailures() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("timeout")));

        assertThatCode(() -> new InteraccionEventPublisher(template, true)
                .publicar(KafkaTopics.PRODUCTO_VISTO, EVENTO)).doesNotThrowAnyException();
    }

    @Test
    void publicar_shouldDoNothing_whenDisabled() {
        new InteraccionEventPublisher(template, false).publicar(KafkaTopics.PRODUCTO_VISTO, EVENTO);

        verifyNoInteractions(template);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> enviado() {
        return mock(SendResult.class);
    }
}
