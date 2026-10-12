package com.camisetas360.orders.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

@Configuration
public class RabbitMQConfig {

        public static final String ORDERS_EXCHANGE = "camisetas360.orders";

        public static final String CHECKOUT_REQUESTED_QUEUE = "orders.checkout-requested.q";

        public static final String CHECKOUT_REQUESTED_ROUTING_KEY = "checkout.requested";

        public static final String ORDER_CREATED_ROUTING_KEY = "order.created";

        @Bean
        public DirectExchange ordersExchange() {
                return new DirectExchange(
                                ORDERS_EXCHANGE,
                                true,
                                false);
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
                        DirectExchange ordersExchange) {
                return BindingBuilder
                                .bind(checkoutRequestedQueue)
                                .to(ordersExchange)
                                .with(CHECKOUT_REQUESTED_ROUTING_KEY);
        }

        @Bean
        public MessageConverter jsonMessageConverter() {
                return new JacksonJsonMessageConverter();
        }

        // The source queue remains unchanged. Install the matching broker policy
        // before upgrading consumers so rejected legacy events are retained.
        @Bean
        public org.springframework.amqp.core.Declarables checkoutDeadLetterTopology() {
                var exchange = new DirectExchange("camisetas360.orders.dlx", true, false);
                var queue = QueueBuilder.durable("orders.checkout-requested.dlq").build();
                return new org.springframework.amqp.core.Declarables(exchange, queue,
                        BindingBuilder.bind(queue).to(exchange).with("checkout.requested.failed"));
        }
}
