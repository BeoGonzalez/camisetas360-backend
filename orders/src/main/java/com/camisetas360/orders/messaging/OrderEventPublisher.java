package com.camisetas360.orders.messaging;

import com.camisetas360.orders.config.RabbitMQConfig;
import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.AmqpException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OrderEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public OrderEventPublisher(
            RabbitTemplate rabbitTemplate, JdbcTemplate jdbc
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.jdbc = jdbc;
    }

    @Transactional
    public void publishOrderCreated(
            OrderCreatedEvent event
    ) {

        log.info(
                "Enqueuing OrderCreatedEvent in outbox orderId={}",
                event.orderId()
        );

        jdbc.update("""
                INSERT INTO order_event_outbox (event_id, payload)
                VALUES (?, CAST(? AS jsonb)) ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), json.writeValueAsString(event));
    }

    public void sendConfirmed(OrderCreatedEvent event) {
        var correlation = new CorrelationData(event.eventId().toString());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDERS_EXCHANGE,
                RabbitMQConfig.ORDER_CREATED_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(event.eventId().toString());
                    return message;
                },
                correlation
        );
        try {
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new AmqpException("order.created was nacked or unroutable");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted awaiting publisher confirm", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AmqpException("Missing publisher confirm", failure);
        }
    }
}
