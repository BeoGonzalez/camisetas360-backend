package com.camisetas360.analytics.integration;

import com.camisetas360.analytics.messaging.KafkaTopics;
import com.camisetas360.analytics.service.AnalyticsService;
import com.camisetas360.analytics.service.MetricasService;
import com.camisetas360.analytics.support.DynamoDbLocalSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo completo: eventos JSON en Kafka (como los publican catalog, carrito y pagos)
 * -> consumidores -> DynamoDB Local -> recomendaciones y metricas.
 */
@SpringBootTest(properties = "app.dynamodb.table-prefix=pipeline-it-")
class KafkaPipelineIT {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:" + UUID.randomUUID();

    @Autowired AnalyticsService analytics;
    @Autowired MetricasService metricas;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.dynamodb.endpoint", DynamoDbLocalSupport::endpoint);
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    @Test
    void eventosDeNavegacionGeneranRecomendacionesYMetricas() {
        Instant ahora = Instant.now();
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            // Otro usuario mira la camiseta de visita: asi analitica conoce ese producto.
            // Se espera a que se procese porque las particiones no garantizan orden entre usuarios.
            producer.send(new ProducerRecord<>(KafkaTopics.PRODUCTO_VISTO, "otro",
                    visto("otro", "CHI-VIS", "Camiseta Chile Visita 2026", ahora)));
            producer.flush();
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    metricas.snapshot().eventosPorTipo().getOrDefault("PRODUCTO_VISTO", 0L) >= 1);

            producer.send(new ProducerRecord<>(KafkaTopics.PRODUCTO_VISTO, USUARIO,
                    visto(USUARIO, "CHI-LOC", "Camiseta Chile Local 2026", ahora.plusMillis(1))));
            producer.send(new ProducerRecord<>(KafkaTopics.PRODUCTO_VISTO, USUARIO, "{json roto"));
            producer.send(new ProducerRecord<>(KafkaTopics.PAGO_FRAUDE_DETECTADO, "orden-1", """
                    {"eventId":"%s","eventType":"PAGO_FRAUDE_DETECTADO","version":1,"ordenId":"orden-1",
                     "monto":39990.0,"ultimos4":"9012","motivo":"4 intentos"}
                    """.formatted(UUID.randomUUID())));
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var guardadas = analytics.obtener(USUARIO).orElseThrow();
            assertThat(guardadas.items()).extracting(r -> r.sku()).contains("CHI-VIS");
            assertThat(guardadas.perfil()).containsKey("Selecciones");
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var snapshot = metricas.snapshot();
            assertThat(snapshot.eventosPorTipo().get("PRODUCTO_VISTO")).isGreaterThanOrEqualTo(2L);
            assertThat(snapshot.alertas()).extracting(a -> a.tipo()).contains("FRAUDE");
        });
    }

    private static String visto(String usuario, String sku, String nombre, Instant cuando) {
        return """
                {"eventId":"%s","eventType":"PRODUCTO_VISTO","version":1,"occurredAt":"%s","source":"catalog",
                 "usuarioId":"%s","producto":{"sku":"%s","nombre":"%s","categoria":"Selecciones","precio":29990.0}}
                """.formatted(UUID.randomUUID(), cuando, usuario, sku, nombre);
    }
}
