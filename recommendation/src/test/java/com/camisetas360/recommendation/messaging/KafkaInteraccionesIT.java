package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.service.RecomendacionService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo de la guia de punta a punta: eventos JSON en Kafka (como los publican catalog y carrito)
 * -> consumidor -> PostgreSQL -> Top 5 ordenado por peso acumulado (vista 1, carrito 2).
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "app.dynamodb.enabled=false", "app.tensorflow.enabled=false"
})
class KafkaInteraccionesIT {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        KAFKA.start();
        POSTGRES.start();
    }

    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:" + UUID.randomUUID();

    @Autowired RecomendacionService recomendaciones;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
        registry.add("RABBITMQ_HOST", () -> "unused.invalid");
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
        POSTGRES.stop();
    }

    @Test
    void vistasYCarritoGeneranTop5OrdenadoPorPeso() {
        UUID duplicado = UUID.randomUUID();
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            // CHI-LOC: vista + carrito = 3. ARG-VIS: dos vistas = 2. BRA-LOC: una vista = 1.
            enviar(producer, KafkaTopics.PRODUCTO_VISTO, evento(UUID.randomUUID(), "PRODUCTO_VISTO", "CHI-LOC", "2026-11-10T12:00:00Z"));
            enviar(producer, KafkaTopics.ITEM_AGREGADO, evento(UUID.randomUUID(), "ITEM_AGREGADO", "CHI-LOC", "2026-11-10T12:01:00Z"));
            enviar(producer, KafkaTopics.PRODUCTO_VISTO, evento(duplicado, "PRODUCTO_VISTO", "ARG-VIS", "2026-11-10T12:02:00Z"));
            enviar(producer, KafkaTopics.PRODUCTO_VISTO, evento(duplicado, "PRODUCTO_VISTO", "ARG-VIS", "2026-11-10T12:02:00Z"));
            enviar(producer, KafkaTopics.PRODUCTO_VISTO, evento(UUID.randomUUID(), "PRODUCTO_VISTO", "ARG-VIS", "2026-11-10T12:03:00Z"));
            enviar(producer, KafkaTopics.PRODUCTO_VISTO, evento(UUID.randomUUID(), "PRODUCTO_VISTO", "BRA-LOC", "2026-11-10T12:04:00Z"));
            enviar(producer, KafkaTopics.PRODUCTO_VISTO, "no-json");
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var top = recomendaciones.top5(USUARIO);
            assertThat(top).extracting(r -> r.sku()).containsExactly("CHI-LOC", "ARG-VIS", "BRA-LOC");
            assertThat(top).extracting(r -> r.puntaje()).containsExactly(3, 2, 1);
            assertThat(top).extracting(r -> r.posicion()).containsExactly(1, 2, 3);
        });
        var primero = recomendaciones.top5(USUARIO).getFirst();
        assertThat(primero.nombre()).isEqualTo("Camiseta CHI-LOC");
        assertThat(primero.categoria()).isEqualTo("Selecciones");
        assertThat(primero.precio()).isEqualTo(29990.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interacciones WHERE event_id = ?", Long.class, duplicado))
                .isEqualTo(1);
    }

    @Test
    void elTop5SeLimitaACincoProductos() {
        String usuario = "11111111-1111-1111-1111-111111111111:" + UUID.randomUUID();
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (int i = 1; i <= 7; i++) {
                producer.send(new ProducerRecord<>(KafkaTopics.PRODUCTO_VISTO, usuario,
                        evento(usuario, UUID.randomUUID(), "PRODUCTO_VISTO", "SKU-" + i, "2026-11-10T12:0" + i + ":00Z")));
            }
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interacciones WHERE usuario_id = ?", Long.class, usuario))
                    .isEqualTo(7);
            // Empate a 1 punto: gana la interaccion mas reciente.
            assertThat(recomendaciones.top5(usuario)).extracting(r -> r.sku())
                    .containsExactly("SKU-7", "SKU-6", "SKU-5", "SKU-4", "SKU-3");
        });
    }

    @Test
    void interaccionQueLaBaseRechazaTerminaEnLaDltSinPerderse() {
        String usuario = "11111111-1111-1111-1111-111111111111:" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // nombre supera VARCHAR(255): error permanente de datos, reintentar no sirve.
        String payload = """
                {"eventId":"%s","eventType":"PRODUCTO_VISTO","version":1,"occurredAt":"2026-11-10T12:00:00Z",
                 "source":"catalog","usuarioId":"%s","producto":{"sku":"LARGO","nombre":"%s"}}
                """.formatted(eventId, usuario, "x".repeat(300));
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(KafkaTopics.PRODUCTO_VISTO, usuario, payload));
            producer.flush();
        }

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(KafkaTopics.INTERACCIONES_DLT));
            List<ConsumerRecord<String, String>> recibidos = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(recibidos::add);
                assertThat(recibidos).anySatisfy(r -> assertThat(r.value()).contains(eventId.toString()));
            });
            var dlt = recibidos.stream().filter(r -> r.value().contains(eventId.toString())).findFirst().orElseThrow();
            assertThat(dlt.value()).isEqualTo(payload);
            assertThat(dlt.key()).isEqualTo(usuario);
            assertThat(new String(dlt.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8))
                    .isEqualTo(KafkaTopics.PRODUCTO_VISTO);
        }
        // La transaccion se revirtio entera: ni la interaccion ni el Top 5 quedaron a medias.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interacciones WHERE usuario_id = ?", Long.class, usuario))
                .isZero();
        assertThat(recomendaciones.top5(usuario)).isEmpty();
    }

    private static void enviar(KafkaProducer<String, String> producer, String topic, String payload) {
        producer.send(new ProducerRecord<>(topic, USUARIO, payload));
    }

    private static String evento(UUID id, String tipo, String sku, String occurredAt) {
        return evento(USUARIO, id, tipo, sku, occurredAt);
    }

    private static String evento(String usuario, UUID id, String tipo, String sku, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"%s","version":1,"occurredAt":"%s","source":"catalog",
                 "usuarioId":"%s","producto":{"sku":"%s","nombre":"Camiseta %s","categoria":"Selecciones","precio":29990.0},
                 "cantidad":1}
                """.formatted(id, tipo, occurredAt, usuario, sku, sku);
    }
}
