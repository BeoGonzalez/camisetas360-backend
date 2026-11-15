package com.camisetas360.carrito.messaging.event;

import java.util.UUID;

/**
 * Evento de interaccion del usuario con la tienda (contrato v1).
 * Ver docs/contracts/analytics-events.md. Este record se duplica en cada
 * productor/consumidor: cualquier cambio debe aplicarse en todas las copias.
 */
public record InteraccionEvento(
        UUID eventId,
        String eventType,
        int version,
        String occurredAt,
        String source,
        String usuarioId,
        ProductoSnapshot producto,
        Integer cantidad,
        EnvioSnapshot envio
) {
    public static final int VERSION = 1;

    public record ProductoSnapshot(String sku, String nombre, String categoria, Double precio) {
    }

    public record EnvioSnapshot(String metodo, String region) {
    }
}
