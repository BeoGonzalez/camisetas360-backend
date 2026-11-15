package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.messaging.OrderEventInbox;
import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo completo hacia la tabla de la guia: interacciones registradas en PostgreSQL (vista por
 * Kafka y compra por RabbitMQ) -> sincronizador -> DynamoDB Local (UserInteractions).
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "app.kafka.listeners-enabled=false",
        "app.dynamodb.poll-ms=200", "app.tensorflow.enabled=false"
})
class SincronizacionDynamoDbIT {

    private static final String TABLA = "UserInteractions";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static final GenericContainer<?> DYNAMODB;

    static {
        DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:2.5.2");
        DYNAMODB.withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");
        DYNAMODB.withExposedPorts(8000);
        DYNAMODB.waitingFor(Wait.forListeningPort());
    }

    // Sin @AfterAll: el sincronizador sigue programado hasta que se cierra el contexto (despues de
    // los tests); si Postgres se detuviera antes, el cierre esperaria el timeout de conexion.
    // Testcontainers (Ryuk) elimina los contenedores al terminar la JVM.
    static {
        POSTGRES.start();
        DYNAMODB.start();
    }

    @Autowired RecomendacionService recomendaciones;
    @Autowired OrderEventInbox inbox;
    @Autowired DynamoDbClient dynamo;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
        registry.add("RABBITMQ_HOST", () -> "unused.invalid");
        registry.add("app.dynamodb.endpoint", () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Test
    void creaLaTablaConLaClaveDeLaGuia() {
        var claves = dynamo.describeTable(r -> r.tableName(TABLA)).table().keySchema();

        assertThat(claves).containsExactlyInAnyOrder(
                KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("timestamp").keyType(KeyType.RANGE).build());
    }

    @Test
    void vistaYCompraLleganADynamoDbConSuPeso() {
        UUID tenant = UUID.randomUUID();
        UUID objeto = UUID.randomUUID();
        String usuario = tenant + ":" + objeto;
        UUID vista = UUID.randomUUID();
        recomendaciones.registrar(new Interaccion(vista, usuario,
                new ProductoFicha("CHI-LOC", "Camiseta Chile Local", "Selecciones", 29990.0),
                Accion.VISTA, "catalog", Instant.parse("2026-11-10T12:00:00Z")));
        inbox.accept("""
                {"eventId":"%s","orderId":77,"tenantId":"%s","objectId":"%s",
                 "occurredAt":"2026-11-10T12:05:00Z","items":[{"sku":"CHI-LOC","quantity":1,"unitPrice":29990.0}]}
                """.formatted(UUID.randomUUID(), tenant, objeto).getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(items(usuario)).hasSize(2));

        var items = items(usuario); // ordenados por timestamp: la vista primero
        assertThat(items).extracting(i -> i.get("action").s()).containsExactly("view", "purchase");
        assertThat(items).extracting(i -> i.get("weight").n()).containsExactly("1", "3");
        assertThat(items).allSatisfy(i -> {
            assertThat(i.get("itemId").s()).isEqualTo("CHI-LOC");
            assertThat(i.get("category").s()).isEqualTo("Selecciones");
        });
        assertThat(items.getFirst().get("timestamp").s()).isEqualTo("2026-11-10T12:00:00.000Z#" + vista);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM interacciones WHERE usuario_id = ? AND dynamo_sincronizado_en IS NULL
                """, Long.class, usuario)).isZero();
    }

    @Test
    void reenviarUnaInteraccionNoLaDuplicaEnDynamoDb() {
        String usuario = UUID.randomUUID() + ":" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        recomendaciones.registrar(new Interaccion(eventId, usuario, new ProductoFicha("ARG-VIS", null, null, null),
                Accion.CARRITO, "carrito", Instant.parse("2026-11-10T13:00:00Z")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(items(usuario)).hasSize(1));

        // Simula una reentrega (p. ej. el commit en PostgreSQL fallo despues de escribir en DynamoDB).
        jdbc.update("UPDATE interacciones SET dynamo_sincronizado_en = NULL WHERE event_id = ?", eventId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT dynamo_sincronizado_en IS NOT NULL FROM interacciones WHERE event_id = ?", Boolean.class, eventId))
                .isTrue());

        assertThat(items(usuario)).hasSize(1);
    }

    private List<Map<String, AttributeValue>> items(String usuario) {
        return dynamo.query(r -> r.tableName(TABLA)
                .keyConditionExpression("userId = :u")
                .expressionAttributeValues(Map.of(":u", AttributeValue.fromS(usuario)))).items();
    }
}
