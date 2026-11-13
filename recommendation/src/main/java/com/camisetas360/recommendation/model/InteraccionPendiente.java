package com.camisetas360.recommendation.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Interaccion registrada en PostgreSQL que aun no se copio a DynamoDB, con la ultima ficha
 * conocida del producto (nombre, categoria y precio pueden faltar).
 */
public record InteraccionPendiente(
        UUID eventId,
        String usuarioId,
        String sku,
        Accion accion,
        int peso,
        String origen,
        Instant ocurridoEn,
        int intentos,
        String nombre,
        String categoria,
        Double precio
) {
}
