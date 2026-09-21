package com.camisetas360.carrito.messaging;

import com.camisetas360.carrito.config.RabbitMQConfig;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class CheckoutEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(CheckoutEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public CheckoutEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishCheckoutRequested(CheckoutRequestedEvent event) {

        log.info(
                "Publishing CheckoutRequestedEvent eventId={}",
                event.eventId()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDERS_EXCHANGE,
                RabbitMQConfig.CHECKOUT_REQUESTED_ROUTING_KEY,
                event
        );
    }
}