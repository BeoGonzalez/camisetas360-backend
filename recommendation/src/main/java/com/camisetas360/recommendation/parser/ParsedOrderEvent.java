package com.camisetas360.recommendation.parser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Evento order.created validado.
 *
 * @param usuarioId {@code <tid>:<oid>} del comprador, o null en payloads legacy sin propietario
 *                  (nunca se infiere desde el email)
 * @param lineas    productos comprados
 */
public record ParsedOrderEvent(
        UUID eventId,
        long orderId,
        String usuarioId,
        List<Linea> lineas,
        Instant occurredAt
) {
    public record Linea(String sku, int quantity, Double unitPrice) {
    }

    public ParsedOrderEvent {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId cannot be null");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("lineas cannot be empty");
        }
        lineas = List.copyOf(lineas);
    }
}
