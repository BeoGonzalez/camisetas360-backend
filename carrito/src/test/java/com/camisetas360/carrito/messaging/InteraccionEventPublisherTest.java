package com.camisetas360.carrito.messaging;

import com.camisetas360.carrito.messaging.event.InteraccionEvento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InteraccionEventPublisherTest {

    private static final InteraccionEvento EVENTO = new InteraccionEvento(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "ITEM_AGREGADO", 1,
            "2026-11-10T12:00:00Z", "carrito", "tenant:object",
            new InteraccionEvento.ProductoSnapshot("SKU-A", "Camiseta", "Liga A", 19.5), 2, null);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publicar_shouldSendImmediately_whenNoTransactionIsActive() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(enviado()));

        new InteraccionEventPublisher(template, true).publicar(KafkaTopics.ITEM_AGREGADO, EVENTO);

        verify(template).send(eq("carrito.item-agregado"), eq("tenant:object"), contains("\"cantidad\":2"));
    }

    @Test
    void publicar_shouldWaitForCommit_whenTransactionIsActive() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(enviado()));
        TransactionSynchronizationManager.initSynchronization();

        new InteraccionEventPublisher(template, true).publicar(KafkaTopics.ITEM_AGREGADO, EVENTO);
        verifyNoInteractions(template);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(template).send(eq("carrito.item-agregado"), eq("tenant:object"), anyString());
    }

    @Test
    void publicar_shouldNeverSend_whenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        new InteraccionEventPublisher(template, true).publicar(KafkaTopics.ITEM_AGREGADO, EVENTO);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(template);
    }

    @Test
    void publicar_shouldNotPropagateBrokerFailures() {
        when(template.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> new InteraccionEventPublisher(template, true)
                .publicar(KafkaTopics.ITEM_AGREGADO, EVENTO)).doesNotThrowAnyException();
    }

    @Test
    void publicar_shouldDoNothing_whenDisabled() {
        new InteraccionEventPublisher(template, false).publicar(KafkaTopics.ITEM_AGREGADO, EVENTO);

        verifyNoInteractions(template);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> enviado() {
        return mock(SendResult.class);
    }
}
