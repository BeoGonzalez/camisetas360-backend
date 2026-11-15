package com.camisetas360.analytics.websocket;

import com.camisetas360.analytics.service.MetricasService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Envia el snapshot de metricas a todos los dashboards conectados cada 2 segundos. */
@Component
public class MetricasPublisher {

    private final SimpMessagingTemplate messaging;
    private final MetricasService metricas;

    public MetricasPublisher(SimpMessagingTemplate messaging, MetricasService metricas) {
        this.messaging = messaging;
        this.metricas = metricas;
    }

    @Scheduled(fixedRateString = "${app.metricas.intervalo-ms:2000}")
    public void publicar() {
        messaging.convertAndSend(StompJwtChannelInterceptor.DESTINO_METRICAS, metricas.snapshot());
    }
}
