package com.camisetas360.telemetria.conexiones;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tabla de conexiones WebSocket activas. PK {@code connectionId} (S); TTL en {@code expiresAt}
 * por si API Gateway no llega a invocar $disconnect (las conexiones viven como maximo 2 horas).
 */
public class ConexionesRepository {

    private final DynamoDbClient client;
    private final String tabla;
    private final Duration ttl;

    public ConexionesRepository(DynamoDbClient client, String tabla, Duration ttl) {
        this.client = client;
        this.tabla = tabla;
        this.ttl = ttl;
    }

    public void guardar(String connectionId, String usuarioId, Instant ahora) {
        client.putItem(r -> r.tableName(tabla).item(Map.of(
                "connectionId", s(connectionId),
                "usuarioId", s(usuarioId),
                "conectadoEn", s(ahora.toString()),
                "expiresAt", AttributeValue.fromN(Long.toString(ahora.plus(ttl).getEpochSecond())))));
    }

    public void eliminar(String connectionId) {
        client.deleteItem(r -> r.tableName(tabla).key(Map.of("connectionId", s(connectionId))));
    }

    /** Todas las conexiones vigentes (scan paginado; ignora las vencidas que el TTL aun no borro). */
    public List<String> activas(Instant ahora) {
        List<String> conexiones = new ArrayList<>();
        Map<String, AttributeValue> desde = null;
        do {
            var peticion = ScanRequest.builder()
                    .tableName(tabla)
                    .projectionExpression("connectionId, expiresAt");
            if (desde != null) {
                peticion.exclusiveStartKey(desde);
            }
            var respuesta = client.scan(peticion.build());
            for (var item : respuesta.items()) {
                var id = item.get("connectionId");
                if (id != null && id.s() != null && !vencida(item.get("expiresAt"), ahora)) {
                    conexiones.add(id.s());
                }
            }
            desde = respuesta.hasLastEvaluatedKey() && !respuesta.lastEvaluatedKey().isEmpty()
                    ? respuesta.lastEvaluatedKey() : null;
        } while (desde != null);
        return conexiones;
    }

    private static boolean vencida(AttributeValue expiresAt, Instant ahora) {
        if (expiresAt == null || expiresAt.n() == null) {
            return false;
        }
        return Long.parseLong(expiresAt.n()) < ahora.getEpochSecond();
    }

    private static AttributeValue s(String valor) {
        return AttributeValue.fromS(valor);
    }
}
