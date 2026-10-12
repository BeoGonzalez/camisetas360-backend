package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.exception.InvalidEventContractException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderCreatedListenerTest {

    private final OrderEventInbox inbox = mock(OrderEventInbox.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final OrderCreatedListener listener = new OrderCreatedListener(inbox, metrics);
    private final byte[] body = new byte[]{1};
    private final Message message = new Message(body, new MessageProperties());

    @Test
    @DisplayName("Accepted first-time delivery increments accepted metric and logs cleanly")
    void acceptedDeliveryIsAcknowledgedAndCounted() {
        when(inbox.accept(body)).thenReturn(true);

        listener.receive(message);

        assertThat(metrics.counter(OrderCreatedListener.METRIC_RECOMMENDATION_EVENTS,
                OrderCreatedListener.TAG_RESULT, OrderCreatedListener.RESULT_ACCEPTED).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate delivery increments duplicate metric")
    void duplicateDeliveryIsAcknowledgedAndCounted() {
        when(inbox.accept(body)).thenReturn(false);

        listener.receive(message);

        assertThat(metrics.counter(OrderCreatedListener.METRIC_RECOMMENDATION_EVENTS,
                OrderCreatedListener.TAG_RESULT, OrderCreatedListener.RESULT_DUPLICATE).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Database failure propagates for bounded retry without being dead-lettered prematurely")
    void databaseFailurePropagatesForBoundedRetry() {
        var failure = new DataAccessResourceFailureException("unavailable");
        when(inbox.accept(body)).thenThrow(failure);

        assertThatThrownBy(() -> listener.receive(message)).isSameAs(failure);
    }

    @Test
    @DisplayName("Invalid payload causes metric increment and AmqpRejectAndDontRequeueException for DLQ")
    void invalidPayloadIsRejectedForDeadLettering() {
        when(inbox.accept(body)).thenThrow(new InvalidEventContractException("invalid contract"));

        assertThatThrownBy(() -> listener.receive(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(InvalidEventContractException.class);

        assertThat(metrics.counter(OrderCreatedListener.METRIC_RECOMMENDATION_EVENTS,
                OrderCreatedListener.TAG_RESULT, OrderCreatedListener.RESULT_INVALID).count()).isEqualTo(1);
    }
}
