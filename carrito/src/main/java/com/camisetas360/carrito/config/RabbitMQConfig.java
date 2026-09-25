package com.camisetas360.carrito.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ORDERS_EXCHANGE = "camisetas360.orders";

    public static final String CHECKOUT_REQUESTED_ROUTING_KEY = "checkout.requested";

    @Bean
    public DirectExchange ordersExchange() {
        return new DirectExchange(
                ORDERS_EXCHANGE,
                true,
                false);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}