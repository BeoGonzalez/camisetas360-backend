package com.camisetas360.carrito.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    // UT-AMQP-001 (carrito)
    @Test
    void rabbitTopology_shouldUseExpectedDestinations_whenBeansAreCreated() {
        var config = new RabbitMQConfig();
        var exchange = config.ordersExchange();

        assertThat(exchange.getName()).isEqualTo("camisetas360.orders");
        assertThat(exchange.getType()).isEqualTo("direct");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }
}
