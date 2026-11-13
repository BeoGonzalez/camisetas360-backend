package com.camisetas360.pago.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerConfigTest {

    private final DefaultKafkaProducerFactory<String, Object> factory =
            (DefaultKafkaProducerFactory<String, Object>) new KafkaProducerConfig("broker:9092").producerFactory();

    @Test
    @DisplayName("Usa el bootstrap inyectado por constructor")
    void usaBootstrapInyectado() {
        assertThat(factory.getConfigurationProperties())
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
    }

    @Test
    @DisplayName("Con el broker caido el envio falla dentro del timeout del relay (5 s) en vez de bloquear 60 s")
    void envioNoBloqueaMasQueElRelay() {
        var props = factory.getConfigurationProperties();
        assertThat(props).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        assertThat(props).containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        assertThat(props).containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
    }

    @Test
    @DisplayName("El cierre del productor no retiene el apagado 30 s (eventos sin ack quedan en el outbox)")
    void cierreRapido() {
        assertThat(factory.getPhysicalCloseTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
