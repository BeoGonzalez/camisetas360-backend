package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.messaging.OrderEventInbox;
import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interacciones concurrentes contra PostgreSQL real: una vista por Kafka y ordenes por RabbitMQ
 * que comparten usuario y productos. Todas las transacciones deben terminar sin deadlock y el
 * Top 5 debe reflejar todos los pesos.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "app.kafka.listeners-enabled=false",
        "app.dynamodb.enabled=false", "app.tensorflow.enabled=false"
})
class RegistroConcurrenteIT {

    private static final int RONDAS = 40;

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @Autowired RecomendacionService recomendaciones;
    @Autowired OrderEventInbox inbox;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
        registry.add("RABBITMQ_HOST", () -> "unused.invalid");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void vistaYOrdenesConcurrentesNoSeBloqueanMutuamente() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int ronda = 0; ronda < RONDAS; ronda++) {
                UUID tenant = UUID.randomUUID();
                UUID comprador = UUID.randomUUID();
                UUID otro = UUID.randomUUID();
                String usuario = tenant + ":" + comprador;
                String a = "A-" + ronda;
                String b = "B-" + ronda;
                var barrera = new CyclicBarrier(3);

                List<Callable<Object>> tareas = List.of(
                        // Kafka: el comprador ve el producto A.
                        () -> {
                            barrera.await();
                            return recomendaciones.registrar(vista(usuario, a));
                        },
                        // RabbitMQ: el comprador compra B y A (en ese orden).
                        () -> {
                            barrera.await();
                            return inbox.accept(orden(tenant, comprador, b, a));
                        },
                        // RabbitMQ: otro usuario compra A y B (orden inverso).
                        () -> {
                            barrera.await();
                            return inbox.accept(orden(tenant, otro, a, b));
                        });

                List<Future<Object>> resultados = new ArrayList<>();
                for (var tarea : tareas) {
                    resultados.add(pool.submit(tarea));
                }
                for (var resultado : resultados) {
                    // Lanza ExecutionException (p. ej. "deadlock detected") si alguna transaccion fallo.
                    resultado.get(30, TimeUnit.SECONDS);
                }

                var top = recomendaciones.top5(usuario);
                assertThat(top).extracting(r -> r.sku()).containsExactly(a, b);
                assertThat(top).extracting(r -> r.puntaje()).containsExactly(4, 3);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Interaccion vista(String usuario, String sku) {
        return new Interaccion(UUID.randomUUID(), usuario, new ProductoFicha(sku, "Camiseta " + sku, "Selecciones", 29990.0),
                Accion.VISTA, "catalog", Instant.parse("2026-11-10T12:00:00Z"));
    }

    private static byte[] orden(UUID tenant, UUID usuario, String... skus) {
        var items = new StringBuilder();
        for (String sku : skus) {
            if (!items.isEmpty()) items.append(',');
            items.append("{\"sku\":\"%s\",\"quantity\":1,\"unitPrice\":29990.0}".formatted(sku));
        }
        return """
                {"eventId":"%s","orderId":%d,"tenantId":"%s","objectId":"%s",
                 "occurredAt":"2026-11-10T12:05:00Z","items":[%s]}
                """.formatted(UUID.randomUUID(), Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000) + 1,
                tenant, usuario, items).getBytes(StandardCharsets.UTF_8);
    }
}
