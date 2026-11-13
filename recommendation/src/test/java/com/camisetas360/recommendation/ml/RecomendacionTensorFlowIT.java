package com.camisetas360.recommendation.ml;

import com.camisetas360.recommendation.messaging.OrderEventInbox;
import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.service.RecomendacionService;
import com.camisetas360.recommendation.service.RecomendacionesConsulta;
import com.camisetas360.recommendation.service.RecomendacionesConsulta.Fuente;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flujo completo de las dos guias en el mismo microservicio: interacciones (vistas, carrito y una
 * compra por RabbitMQ) en PostgreSQL -> entrenamiento TensorFlow -> GET de recomendaciones.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "app.kafka.listeners-enabled=false",
        "app.dynamodb.enabled=false",
        // El test dispara el entrenamiento a mano; el programado no debe correr en paralelo.
        "app.tensorflow.retraso-inicial-ms=3600000"
})
class RecomendacionTensorFlowIT {

    // Sin @AfterAll: el scheduler vive hasta que cierra el contexto; Ryuk elimina el contenedor.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @Autowired RecomendacionService recomendaciones;
    @Autowired OrderEventInbox inbox;
    @Autowired ModeloTensorFlow modelo;
    @Autowired RecomendacionesConsulta consulta;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
        registry.add("RABBITMQ_HOST", () -> "unused.invalid");
    }

    @Test
    void elModeloRecomiendaLoQueCompranUsuariosParecidos() {
        String tenant = UUID.randomUUID().toString();
        for (int n = 0; n < 8; n++) {
            for (String sku : List.of("CHI-LOC", "CHI-VIS", "CHI-RET", "CHI-ARQ")) carrito(tenant + ":chi-" + n, sku);
            for (String sku : List.of("ARG-LOC", "ARG-VIS", "ARG-RET", "ARG-ARQ")) carrito(tenant + ":arg-" + n, sku);
        }
        UUID objeto = UUID.randomUUID();
        String nuevo = tenant + ":" + objeto;
        recomendaciones.registrar(new Interaccion(UUID.randomUUID(), nuevo,
                new ProductoFicha("CHI-LOC", "Camiseta Chile Local", "Selecciones", 29990.0),
                Accion.VISTA, "catalog", Instant.now()));
        inbox.accept("""
                {"eventId":"%s","orderId":91,"tenantId":"%s","objectId":"%s",
                 "items":[{"sku":"CHI-VIS","quantity":1,"unitPrice":29990.0}]}
                """.formatted(UUID.randomUUID(), tenant, objeto).getBytes(StandardCharsets.UTF_8));

        // Antes del primer entrenamiento rige el Top 5 ponderado de la guia simple.
        assertThat(consulta.consultar(nuevo).fuente()).isEqualTo(Fuente.PESOS);

        modelo.reentrenar();
        var resultado = consulta.consultar(nuevo);

        assertThat(resultado.fuente()).isEqualTo(Fuente.MODELO);
        assertThat(resultado.versionModelo()).isNotBlank();
        var skus = resultado.items().stream().map(RecomendacionesConsulta.Item::sku).toList();
        assertThat(skus).doesNotContain("CHI-VIS"); // ya comprado
        assertThat(skus.subList(0, 3)).containsExactlyInAnyOrder("CHI-LOC", "CHI-RET", "CHI-ARQ");
        assertThat(resultado.items().getFirst().score()).isBetween(0.0, 1.0);
        assertThat(resultado.items()).filteredOn(i -> i.sku().equals("CHI-LOC")).singleElement()
                .satisfies(i -> assertThat(i.nombre()).isEqualTo("Camiseta Chile Local"));

        // Usuario sin historial: populares del modelo.
        assertThat(consulta.consultar(tenant + ":" + UUID.randomUUID()).fuente()).isEqualTo(Fuente.POPULARES);
    }

    private void carrito(String usuario, String sku) {
        recomendaciones.registrar(new Interaccion(UUID.randomUUID(), usuario, new ProductoFicha(sku, null, null, null),
                Accion.CARRITO, "carrito", Instant.now()));
    }
}
