package com.camisetas360.carrito.messaging;

import com.camisetas360.carrito.messaging.event.CheckoutItemEvent;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
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
class CheckoutEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private CheckoutEventPublisher publisher;

    // UT-CART-006
    @Test
    void publishCheckoutRequested_shouldUseCheckoutRouting_whenEventIsValid() {
        var event = event();

        publisher.publishCheckoutRequested(event);

        verify(rabbitTemplate, times(1)).convertAndSend(
                "camisetas360.orders", "checkout.requested", event);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    // UT-CART-007
    @Test
    void publishCheckoutRequested_shouldPropagateFailure_whenRabbitTemplateFails() {
        var event = event();
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(rabbitTemplate).convertAndSend(
                "camisetas360.orders", "checkout.requested", event);

        assertThat(assertThrows(AmqpException.class,
                () -> publisher.publishCheckoutRequested(event))).isSameAs(failure);

        verify(rabbitTemplate, times(1)).convertAndSend(
                "camisetas360.orders", "checkout.requested", event);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    private static CheckoutRequestedEvent event() {
        return new CheckoutRequestedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "buyer@example.test",
                List.of(new CheckoutItemEvent("SKU-A", 2, 19.5)),
                Instant.parse("2026-01-01T12:00:00Z"));
    }
}
