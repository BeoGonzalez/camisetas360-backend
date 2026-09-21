package com.camisetas360.orders.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ORDERS_EXCHANGE =
            "camisetas360.orders";

    public static final String CHECKOUT_REQUESTED_QUEUE =
            "orders.checkout-requested.q";

    public static final String CHECKOUT_REQUESTED_ROUTING_KEY =
            "checkout.requested";

    public static final String ORDER_CREATED_ROUTING_KEY =
            "order.created";

    @Bean
    public TopicExchange ordersExchange() {
        return new TopicExchange(
                ORDERS_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue checkoutRequestedQueue() {
        return QueueBuilder
                .durable(CHECKOUT_REQUESTED_QUEUE)
                .build();
    }

    @Bean
    public Binding checkoutRequestedBinding(
            Queue checkoutRequestedQueue,
            TopicExchange ordersExchange
    ) {
        return BindingBuilder
                .bind(checkoutRequestedQueue)
                .to(ordersExchange)
                .with(CHECKOUT_REQUESTED_ROUTING_KEY);
    }
}