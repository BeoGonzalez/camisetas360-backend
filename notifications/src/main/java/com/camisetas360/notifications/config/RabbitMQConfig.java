package com.camisetas360.notifications.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ORDERS_EXCHANGE =
            "camisetas360.orders";

    public static final String ORDER_CREATED_ROUTING_KEY =
            "order.created";

    public static final String NOTIFICATIONS_ORDER_CREATED_QUEUE =
            "notifications.order-created.q";

    @Bean
    public DirectExchange ordersExchange() {
        return new DirectExchange(
                ORDERS_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue notificationsOrderCreatedQueue() {
        return new Queue(
                NOTIFICATIONS_ORDER_CREATED_QUEUE,
                true
        );
    }

    @Bean
    public Binding notificationsOrderCreatedBinding(
            Queue notificationsOrderCreatedQueue,
            DirectExchange ordersExchange
    ) {
        return BindingBuilder
                .bind(notificationsOrderCreatedQueue)
                .to(ordersExchange)
                .with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}