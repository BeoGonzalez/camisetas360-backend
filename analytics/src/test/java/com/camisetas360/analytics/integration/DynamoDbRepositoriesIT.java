package com.camisetas360.analytics.integration;

import com.camisetas360.analytics.model.InteraccionEvento;
import com.camisetas360.analytics.model.Recomendacion;
import com.camisetas360.analytics.repository.DynamoDbTableInitializer;
import com.camisetas360.analytics.repository.DynamoDbTables;
import com.camisetas360.analytics.repository.EventoRepository;
import com.camisetas360.analytics.repository.ProductoRepository;
import com.camisetas360.analytics.repository.RecomendacionRepository;
import com.camisetas360.analytics.support.DynamoDbLocalSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.camisetas360.analytics.support.Eventos.agregado;
import static com.camisetas360.analytics.support.Eventos.evento;
import static com.camisetas360.analytics.support.Eventos.visto;
import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbRepositoriesIT {

    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");

    private DynamoDbClient client;
    private EventoRepository eventos;
    private ProductoRepository productos;
    private RecomendacionRepository recomendaciones;
    private String usuario;

    @BeforeEach
    void setUp() {
        client = DynamoDbLocalSupport.client();
        // Prefijo unico por test: tablas aisladas en la misma instancia de DynamoDB Local.
        var tables = new DynamoDbTables("it-" + UUID.randomUUID().toString().substring(0, 8) + "-");
        new DynamoDbTableInitializer(client, tables).crearTablas();
        eventos = new EventoRepository(client, tables, 90);
        productos = new ProductoRepository(client, tables);
        recomendaciones = new RecomendacionRepository(client, tables);
        usuario = "t:" + UUID.randomUUID();
    }

    @Test
    void initializerEsIdempotenteYCreaLasTresTablas() {
        var tables = new DynamoDbTables("it-idem-" + UUID.randomUUID().toString().substring(0, 6) + "-");
        var initializer = new DynamoDbTableInitializer(client, tables);
        initializer.crearTablas();
        initializer.crearTablas();

        assertThat(client.listTables().tableNames())
                .contains(tables.eventos(), tables.productos(), tables.recomendaciones());
    }

    @Test
    void productos_tienenStreamNewImageParaLaTelemetria() {
        var tables = new DynamoDbTables("it-stream-" + UUID.randomUUID().toString().substring(0, 6) + "-");
        new DynamoDbTableInitializer(client, tables).crearTablas();

        var productos = client.describeTable(r -> r.tableName(tables.productos())).table();
        assertThat(productos.streamSpecification().streamEnabled()).isTrue();
        assertThat(productos.streamSpecification().streamViewType()).isEqualTo(StreamViewType.NEW_IMAGE);
        assertThat(productos.latestStreamArn()).isNotBlank();
        assertThat(client.describeTable(r -> r.tableName(tables.eventos())).table().streamSpecification()).isNull();
    }

    @Test
    void productos_existenteSinStream_seLeActivaAlArrancar() {
        var tables = new DynamoDbTables("it-upd-" + UUID.randomUUID().toString().substring(0, 6) + "-");
        new DynamoDbTableInitializer(client, tables, false).crearTablas();
        assertThat(client.describeTable(r -> r.tableName(tables.productos())).table().streamSpecification()).isNull();

        new DynamoDbTableInitializer(client, tables, true).crearTablas();

        var productos = client.describeTable(r -> r.tableName(tables.productos())).table();
        assertThat(productos.streamSpecification().streamEnabled()).isTrue();
        assertThat(productos.streamSpecification().streamViewType()).isEqualTo(StreamViewType.NEW_IMAGE);
    }

    @Test
    void eventos_sonIdempotentesYSeLeenDelMasRecienteAlMasAntiguo() {
        var primero = evento(InteraccionEvento.PRODUCTO_VISTO, usuario, "A", "Camiseta A", "Liga", 10.0, null, T0);
        var segundo = evento(InteraccionEvento.ITEM_AGREGADO, usuario, "B", "Camiseta B", null, null, 2, T0.plusSeconds(5));

        assertThat(eventos.guardarSiNoExiste(primero)).isTrue();
        assertThat(eventos.guardarSiNoExiste(primero)).isFalse();
        assertThat(eventos.guardarSiNoExiste(segundo)).isTrue();

        var recientes = eventos.recientes(usuario, 10);
        assertThat(recientes).extracting(InteraccionEvento::eventId).containsExactly(segundo.eventId(), primero.eventId());
        assertThat(recientes.get(0)).usingRecursiveComparison().isEqualTo(segundo);
        assertThat(recientes.get(1)).usingRecursiveComparison().isEqualTo(primero);
        assertThat(eventos.recientes(usuario, 1)).hasSize(1);
        assertThat(eventos.recientes("otro", 10)).isEmpty();
    }

    @Test
    void productos_acumulanVistasYConservanLaFicha() {
        productos.registrar(visto("A", "Camiseta A", "Liga", 10.0, T0));
        productos.registrar(visto("A", "Camiseta A 2026", "Liga", 12.0, T0));
        var info = productos.registrar(agregado("A", "Camiseta A 2026", "Liga", 12.0, 3, T0));

        assertThat(info.vistas()).isEqualTo(2);
        assertThat(info.nombre()).isEqualTo("Camiseta A 2026");
        assertThat(info.precio()).isEqualTo(12.0);
        assertThat(productos.catalogo()).singleElement().isEqualTo(info);
    }

    @Test
    void recomendaciones_conservanItemsYOrdenDelPerfil() {
        Map<String, Double> perfil = new LinkedHashMap<>();
        perfil.put("Selecciones", 1.0);
        perfil.put("La Liga", 0.333);
        var items = List.of(
                new Recomendacion("CHI-VIS", "Chile Visita", "Selecciones", 27990.0, 0.993, "Parecida a Chile Local"),
                new Recomendacion("RM-RET", "Real Madrid Retro", null, null, 0.2, "Popular en la tienda"));

        recomendaciones.guardar(usuario, items, perfil, T0);

        var guardadas = recomendaciones.buscar(usuario).orElseThrow();
        assertThat(guardadas.items()).containsExactlyElementsOf(items);
        assertThat(guardadas.perfil().keySet()).containsExactly("Selecciones", "La Liga");
        assertThat(guardadas.generadoEn()).isEqualTo(T0.toString());
        assertThat(recomendaciones.buscar("desconocido")).isEmpty();
    }
}
