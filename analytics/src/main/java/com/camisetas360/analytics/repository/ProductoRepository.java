package com.camisetas360.analytics.repository;

import com.camisetas360.analytics.model.InteraccionEvento;
import com.camisetas360.analytics.model.ProductoInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.camisetas360.analytics.repository.EventoRepository.n;
import static com.camisetas360.analytics.repository.EventoRepository.s;

/**
 * Productos conocidos por analitica (candidatos a recomendar) con contadores de popularidad.
 * Se mantiene una copia en memoria que se recarga desde DynamoDB cada 5 minutos.
 */
@Repository
public class ProductoRepository {

    private static final Duration RECARGA = Duration.ofMinutes(5);

    private final DynamoDbClient client;
    private final DynamoDbTables tables;
    private final Clock clock;
    private final Map<String, ProductoInfo> cache = new ConcurrentHashMap<>();
    private volatile Instant cargadoEn = Instant.EPOCH;

    @Autowired
    public ProductoRepository(DynamoDbClient client, DynamoDbTables tables) {
        this(client, tables, Clock.systemUTC());
    }

    ProductoRepository(DynamoDbClient client, DynamoDbTables tables, Clock clock) {
        this.client = client;
        this.tables = tables;
        this.clock = clock;
    }

    /** Actualiza la ficha del producto y suma vistas o unidades agregadas segun el tipo de evento. */
    public ProductoInfo registrar(InteraccionEvento evento) {
        var producto = evento.producto();
        List<String> set = new ArrayList<>();
        Map<String, AttributeValue> valores = new HashMap<>();
        set.add("actualizadoEn = :t");
        valores.put(":t", s(Instant.now(clock).toString()));
        if (producto.nombre() != null) {
            set.add("nombre = :nombre");
            valores.put(":nombre", s(producto.nombre()));
        }
        if (producto.categoria() != null) {
            set.add("categoria = :categoria");
            valores.put(":categoria", s(producto.categoria()));
        }
        if (producto.precio() != null) {
            set.add("precio = :precio");
            valores.put(":precio", n(producto.precio()));
        }
        long vistas = InteraccionEvento.PRODUCTO_VISTO.equals(evento.eventType()) ? 1 : 0;
        long agregados = InteraccionEvento.ITEM_AGREGADO.equals(evento.eventType()) && evento.cantidad() != null
                ? evento.cantidad() : 0;
        valores.put(":v", n(vistas));
        valores.put(":a", n(agregados));

        var response = client.updateItem(UpdateItemRequest.builder()
                .tableName(tables.productos())
                .key(Map.of("sku", s(producto.sku())))
                .updateExpression("SET " + String.join(", ", set) + " ADD vistas :v, agregados :a")
                .expressionAttributeValues(valores)
                .returnValues(ReturnValue.ALL_NEW)
                .build());

        ProductoInfo info = aProducto(response.attributes());
        cache.put(info.sku(), info);
        return info;
    }

    public Collection<ProductoInfo> catalogo() {
        if (cache.isEmpty() || cargadoEn.isBefore(Instant.now(clock).minus(RECARGA))) {
            recargar();
        }
        return List.copyOf(cache.values());
    }

    private synchronized void recargar() {
        Map<String, AttributeValue> desde = null;
        Map<String, ProductoInfo> leidos = new HashMap<>();
        do {
            var builder = ScanRequest.builder().tableName(tables.productos());
            if (desde != null) {
                builder.exclusiveStartKey(desde);
            }
            var response = client.scan(builder.build());
            response.items().forEach(item -> {
                var info = aProducto(item);
                leidos.put(info.sku(), info);
            });
            desde = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (desde != null);
        cache.putAll(leidos);
        cargadoEn = Instant.now(clock);
    }

    private static ProductoInfo aProducto(Map<String, AttributeValue> item) {
        return new ProductoInfo(
                item.get("sku").s(),
                item.containsKey("nombre") ? item.get("nombre").s() : null,
                item.containsKey("categoria") ? item.get("categoria").s() : null,
                item.containsKey("precio") ? Double.valueOf(item.get("precio").n()) : null,
                item.containsKey("vistas") ? Long.parseLong(item.get("vistas").n()) : 0);
    }
}
