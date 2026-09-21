package com.camisetas360.orders.messaging;

import com.camisetas360.orders.config.RabbitMQConfig;
import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OrderEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(
            RabbitTemplate rabbitTemplate
    ) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishOrderCreated(
            OrderCreatedEvent event
    ) {

        log.info(
                "Publishing OrderCreatedEvent orderId={}",
                event.orderId()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDERS_EXCHANGE,
                RabbitMQConfig.ORDER_CREATED_ROUTING_KEY,
                event
        );
    }
}