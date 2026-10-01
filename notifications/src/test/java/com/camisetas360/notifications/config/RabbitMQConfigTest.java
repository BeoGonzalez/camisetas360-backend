package com.camisetas360.notifications.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    // UT-AMQP-001 (notifications)
    @Test
    void rabbitTopology_shouldUseExpectedDestinations_whenBeansAreCreated() {
        var config = new RabbitMQConfig();
        var exchange = config.ordersExchange();

        assertThat(exchange.getName()).isEqualTo("camisetas360.orders");
        assertThat(exchange.getType()).isEqualTo("direct");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();

        var queue = config.notificationsOrderCreatedQueue();
        var binding = config.notificationsOrderCreatedBinding(queue, exchange);
        assertThat(queue.getName()).isEqualTo("notifications.order-created.q");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(binding.getExchange()).isEqualTo("camisetas360.orders");
        assertThat(binding.getDestination()).isEqualTo("notifications.order-created.q");
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getRoutingKey()).isEqualTo("order.created");
    }
}
