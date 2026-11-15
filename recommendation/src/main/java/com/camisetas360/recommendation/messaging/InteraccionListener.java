package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.service.RecomendacionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Consumidor Kafka de la guia: cada vista o item agregado al carrito suma peso al producto y
 * recalcula el Top 5 del usuario. Si la base de datos falla, la excepcion se propaga y el
 * contenedor reintenta con backoff (ver KafkaConsumerConfig).
 */
@Component
public class InteraccionListener {

    static final String METRICA = "recommendation.interacciones";

    private final InteraccionEventoParser parser;
    private final RecomendacionService recomendaciones;
    private final MeterRegistry metrics;

    public InteraccionListener(InteraccionEventoParser parser, RecomendacionService recomendaciones,
                               MeterRegistry metrics) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.recomendaciones = Objects.requireNonNull(recomendaciones, "recomendaciones");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @KafkaListener(
            id = "recommendation-interacciones",
            groupId = KafkaTopics.GRUPO,
            topics = {KafkaTopics.PRODUCTO_VISTO, KafkaTopics.ITEM_AGREGADO},
            autoStartup = "${app.kafka.listeners-enabled:true}")
    public void onInteraccion(String payload) {
        var interaccion = parser.parse(payload);
        if (interaccion.isEmpty()) {
            metrics.counter(METRICA, "result", "invalid").increment();
            return;
        }
        boolean nueva = recomendaciones.registrar(interaccion.get());
        metrics.counter(METRICA, "result", nueva ? "accepted" : "duplicate").increment();
    }
}
