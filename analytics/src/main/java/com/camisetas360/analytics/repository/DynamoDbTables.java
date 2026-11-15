package com.camisetas360.analytics.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Nombres de las tablas DynamoDB:
 * - eventos:          PK usuarioId, SK sk (occurredAt#eventId). Historial con TTL (expiresAt).
 * - productos:        PK sku. Productos conocidos y contadores de vistas/agregados.
 * - recomendaciones:  PK usuarioId. Recomendaciones y perfil de navegacion vigentes.
 */
@Component
public class DynamoDbTables {

    private final String prefix;

    public DynamoDbTables(@Value("${app.dynamodb.table-prefix:camisetas360-analytics-}") String prefix) {
        this.prefix = prefix;
    }

    public String eventos() {
        return prefix + "eventos";
    }

    public String productos() {
        return prefix + "productos";
    }

    public String recomendaciones() {
        return prefix + "recomendaciones";
    }
}
