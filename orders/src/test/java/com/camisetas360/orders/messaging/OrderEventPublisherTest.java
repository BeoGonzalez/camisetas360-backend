package com.camisetas360.orders.messaging;

import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import com.camisetas360.orders.messaging.event.OrderItemEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @InjectMocks
    private OrderEventPublisher publisher;

    // UT-ORD-014
    @Test
    void publishOrderCreated_shouldUseOrderCreatedRouting_whenEventIsValid() {
        var event = event();

        publisher.publishOrderCreated(event);

        verify(rabbitTemplate, times(1)).convertAndSend(
                "camisetas360.orders", "order.created", event);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    // UT-ORD-015
    @Test
    void publishOrderCreated_shouldPropagateFailure_whenRabbitTemplateFails() {
        var event = event();
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(rabbitTemplate).convertAndSend(
                "camisetas360.orders", "order.created", event);

        assertThat(assertThrows(AmqpException.class,
                () -> publisher.publishOrderCreated(event))).isSameAs(failure);

        verify(rabbitTemplate, times(1)).convertAndSend(
                "camisetas360.orders", "order.created", event);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                42L, "buyer@example.test", 39.0,
                List.of(new OrderItemEvent("SKU-A", 2, 19.5)),
                Instant.parse("2026-01-01T12:00:00Z"));
    }
}
