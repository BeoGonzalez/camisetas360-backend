package com.camisetas360.analytics.messaging;

import com.camisetas360.analytics.service.AnalyticsService;
import com.camisetas360.analytics.service.MetricasService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidores Kafka.
 * - analytics-recommendations: interacciones de catalog y carrito.
 * - analytics-dashboard: alertas de fraude publicadas por pagos (solo para el dashboard).
 * Si el procesamiento falla (p. ej. DynamoDB no disponible) el error se propaga y el
 * contenedor reintenta con backoff antes de descartar el registro.
 */
@Component
public class InteraccionListener {

    private final EventoParser parser;
    private final AnalyticsService analytics;
    private final MetricasService metricas;

    public InteraccionListener(EventoParser parser, AnalyticsService analytics, MetricasService metricas) {
        this.parser = parser;
        this.analytics = analytics;
        this.metricas = metricas;
    }

    @KafkaListener(
            id = "analytics-interacciones",
            groupId = "analytics-recommendations",
            topics = {KafkaTopics.PRODUCTO_VISTO, KafkaTopics.ITEM_AGREGADO, KafkaTopics.ITEM_ELIMINADO,
                    KafkaTopics.ENVIO_ACTUALIZADO},
            autoStartup = "${app.kafka.listeners-enabled:true}")
    public void onInteraccion(String payload) {
        parser.interaccion(payload).ifPresent(analytics::registrar);
    }

    @KafkaListener(
            id = "analytics-fraude",
            groupId = "analytics-dashboard",
            topics = KafkaTopics.PAGO_FRAUDE_DETECTADO,
            autoStartup = "${app.kafka.listeners-enabled:true}")
    public void onFraude(String payload) {
        parser.fraude(payload).ifPresent(metricas::registrarFraude);
    }
}
