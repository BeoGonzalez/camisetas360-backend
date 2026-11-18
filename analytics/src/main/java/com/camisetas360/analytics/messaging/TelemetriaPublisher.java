package com.camisetas360.analytics.messaging;

import com.camisetas360.analytics.model.ProductoTelemetria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica en {@code telemetria.productos-actualizados} el mismo estado de producto que llega al
 * WebSocket de telemetria, para verlo en Kafka UI. Clave = SKU (orden por producto). Es best-effort:
 * un fallo de Kafka se registra y no interrumpe el procesamiento (la fuente de verdad es DynamoDB).
 */
@Component
public class TelemetriaPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetriaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper json = JsonMapper.builder().build();
    private final boolean enabled;

    public TelemetriaPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               @Value("${app.kafka.telemetria-enabled:true}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
    }

    public void publicar(ProductoTelemetria producto) {
        if (!enabled || producto == null) {
            return;
        }
        try {
            kafkaTemplate.send(KafkaTopics.TELEMETRIA_PRODUCTOS, producto.sku(), json.writeValueAsString(producto))
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            advertir(producto, failure);
                        }
                    });
        } catch (RuntimeException failure) {
            advertir(producto, failure);
        }
    }

    private static void advertir(ProductoTelemetria producto, Throwable failure) {
        log.warn("No se pudo publicar la telemetria sku={}: {}", producto.sku(), failure.getMessage());
    }
}
