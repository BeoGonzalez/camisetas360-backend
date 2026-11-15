package com.camisetas360.analytics.repository;

import com.camisetas360.analytics.model.InteraccionEvento;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Historial de interacciones por usuario (tabla eventos). */
@Repository
public class EventoRepository {

    private final DynamoDbClient client;
    private final DynamoDbTables tables;
    private final Duration retencion;

    public EventoRepository(DynamoDbClient client, DynamoDbTables tables,
                            @Value("${app.dynamodb.eventos-ttl-dias:90}") long ttlDias) {
        this.client = client;
        this.tables = tables;
        this.retencion = Duration.ofDays(ttlDias);
    }

    /**
     * Guarda el evento si no existia. La clave incluye el eventId, por lo que una entrega
     * repetida de Kafka (at-least-once) devuelve false y no se procesa dos veces.
     */
    public boolean guardarSiNoExiste(InteraccionEvento evento) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("usuarioId", s(evento.usuarioId()));
        item.put("sk", s(evento.occurredAt() + "#" + evento.eventId()));
        item.put("eventId", s(evento.eventId().toString()));
        item.put("eventType", s(evento.eventType()));
        item.put("occurredAt", s(evento.occurredAt()));
        putIfPresent(item, "source", evento.source());
        if (evento.producto() != null) {
            putIfPresent(item, "sku", evento.producto().sku());
            putIfPresent(item, "nombre", evento.producto().nombre());
            putIfPresent(item, "categoria", evento.producto().categoria());
            if (evento.producto().precio() != null) {
                item.put("precio", n(evento.producto().precio()));
            }
        }
        if (evento.cantidad() != null) {
            item.put("cantidad", n(evento.cantidad()));
        }
        if (evento.envio() != null) {
            putIfPresent(item, "metodoEnvio", evento.envio().metodo());
            putIfPresent(item, "region", evento.envio().region());
        }
        item.put("expiresAt", n(expiracion(evento.occurredAt()).getEpochSecond()));

        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tables.eventos())
                    .item(item)
                    .conditionExpression("attribute_not_exists(sk)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException duplicado) {
            return false;
        }
    }

    /** Ultimos eventos del usuario, del mas reciente al mas antiguo. */
    public List<InteraccionEvento> recientes(String usuarioId, int limite) {
        var response = client.query(QueryRequest.builder()
                .tableName(tables.eventos())
                .keyConditionExpression("usuarioId = :u")
                .expressionAttributeValues(Map.of(":u", s(usuarioId)))
                .scanIndexForward(false)
                .limit(limite)
                .build());
        return response.items().stream().map(EventoRepository::aEvento).toList();
    }

    private Instant expiracion(String occurredAt) {
        try {
            return Instant.parse(occurredAt).plus(retencion);
        } catch (DateTimeParseException invalid) {
            return Instant.now().plus(retencion);
        }
    }

    private static InteraccionEvento aEvento(Map<String, AttributeValue> item) {
        InteraccionEvento.ProductoSnapshot producto = item.containsKey("sku")
                ? new InteraccionEvento.ProductoSnapshot(texto(item, "sku"), texto(item, "nombre"),
                texto(item, "categoria"), item.containsKey("precio") ? Double.valueOf(item.get("precio").n()) : null)
                : null;
        InteraccionEvento.EnvioSnapshot envio = item.containsKey("metodoEnvio")
                ? new InteraccionEvento.EnvioSnapshot(texto(item, "metodoEnvio"), texto(item, "region"))
                : null;
        Integer cantidad = item.containsKey("cantidad") ? Integer.valueOf(item.get("cantidad").n()) : null;
        return new InteraccionEvento(UUID.fromString(texto(item, "eventId")), texto(item, "eventType"),
                InteraccionEvento.VERSION, texto(item, "occurredAt"), texto(item, "source"), texto(item, "usuarioId"),
                producto, cantidad, envio);
    }

    private static String texto(Map<String, AttributeValue> item, String nombre) {
        AttributeValue value = item.get(nombre);
        return value == null ? null : value.s();
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String nombre, String valor) {
        if (valor != null && !valor.isBlank()) {
            item.put(nombre, s(valor));
        }
    }

    static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    static AttributeValue n(Number value) {
        return AttributeValue.builder().n(value.toString()).build();
    }
}
