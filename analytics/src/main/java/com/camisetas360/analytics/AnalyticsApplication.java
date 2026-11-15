package com.camisetas360.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ms-analytics-recommendations: consume eventos de navegacion desde Kafka, persiste historial,
 * perfiles y recomendaciones en DynamoDB y publica metricas en tiempo real por WebSocket.
 */
@SpringBootApplication
@EnableScheduling
public class AnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
