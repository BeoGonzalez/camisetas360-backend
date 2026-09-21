package com.camisetas360.orders.messaging.listener;

import com.camisetas360.orders.config.RabbitMQConfig;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CheckoutRequestedListener {

    private static final Logger log =
            LoggerFactory.getLogger(CheckoutRequestedListener.class);

    private final OrderService orderService;

    public CheckoutRequestedListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(
            queues = RabbitMQConfig.CHECKOUT_REQUESTED_QUEUE
    )
    public void handleCheckoutRequested(
            CheckoutRequestedEvent event
    ) {

        log.info(
                "Received CheckoutRequestedEvent eventId={}",
                event.eventId()
        );

        Order order = orderService.createOrder(event);

        log.info(
                "Order created successfully orderId={}",
                order.getId()
        );
    }
}