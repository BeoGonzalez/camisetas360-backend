package com.camisetas360.orders.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    // UT-AMQP-001 (orders)
    @Test
    void rabbitTopology_shouldUseExpectedDestinations_whenBeansAreCreated() {
        var config = new RabbitMQConfig();
        var exchange = config.ordersExchange();

        assertThat(exchange.getName()).isEqualTo("camisetas360.orders");
        assertThat(exchange.getType()).isEqualTo("direct");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();

        var queue = config.checkoutRequestedQueue();
        var binding = config.checkoutRequestedBinding(queue, exchange);
        assertThat(queue.getName()).isEqualTo("orders.checkout-requested.q");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(binding.getExchange()).isEqualTo("camisetas360.orders");
        assertThat(binding.getDestination()).isEqualTo("orders.checkout-requested.q");
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getRoutingKey()).isEqualTo("checkout.requested");
    }
}
