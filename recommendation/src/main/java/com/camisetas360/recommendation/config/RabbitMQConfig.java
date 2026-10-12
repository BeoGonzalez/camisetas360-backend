package com.camisetas360.recommendation.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String QUEUE = "recommendation.order-created.q";
    public static final String DLQ = "recommendation.order-created.dlq";
    public static final String DLX = "camisetas360.recommendation.dlx";

    @Bean
    public Declarables recommendationTopology() {
        var orders = new DirectExchange("camisetas360.orders", true, false);
        var deadLetters = new DirectExchange(DLX, true, false);
        var queue = QueueBuilder.durable(QUEUE).quorum()
                .deadLetterExchange(DLX).deadLetterRoutingKey("order.created.failed")
                .withArgument("x-delivery-limit", 5)
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-dead-letter-strategy", "at-least-once").build();
        var dlq = QueueBuilder.durable(DLQ).quorum().build();
        return new Declarables(orders, deadLetters, queue, dlq,
                BindingBuilder.bind(queue).to(orders).with("order.created"),
                BindingBuilder.bind(dlq).to(deadLetters).with("order.created.failed"));
    }
}
