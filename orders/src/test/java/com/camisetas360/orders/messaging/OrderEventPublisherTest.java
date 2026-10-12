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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Mock
    private JdbcTemplate jdbc;
    @InjectMocks
    private OrderEventPublisher publisher;

    // UT-ORD-014
    @Test
    void publishOrderCreated_shouldUseOrderCreatedRouting_whenEventIsValid() {
        var event = event();

        doAnswer(invocation -> {
            MessagePostProcessor processor = invocation.getArgument(3);
            var message = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("camisetas360.orders"), eq("order.created"), eq(event),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        publisher.sendConfirmed(event);
    }

    // UT-ORD-015
    @Test
    void publishOrderCreated_shouldPropagateFailure_whenRabbitTemplateFails() {
        var event = event();
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(rabbitTemplate).convertAndSend(
                eq("camisetas360.orders"), eq("order.created"), eq(event),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThat(assertThrows(AmqpException.class,
                () -> publisher.sendConfirmed(event))).isSameAs(failure);
    }

    @Test
    void publishOrderCreated_shouldPersistWithoutContactingUnavailableBroker() {
        var event = event();
        publisher.publishOrderCreated(event);
        verify(jdbc).update(anyString(), eq(event.eventId()), contains(event.eventId().toString()));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void sendConfirmed_shouldFailOnNack() {
        assertRejected(false, false);
    }

    @Test
    void sendConfirmed_shouldFailWhenReturnedEvenIfAcked() {
        assertRejected(true, true);
    }

    private void assertRejected(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            if (returned) correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE", "camisetas360.orders", "order.created"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, "test"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThrows(AmqpException.class, () -> publisher.sendConfirmed(event()));
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                42L, "buyer@example.test", 39.0,
                List.of(new OrderItemEvent("SKU-A", 2, 19.5)),
                Instant.parse("2026-01-01T12:00:00Z"), java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"));
    }
}
