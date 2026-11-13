package com.camisetas360.analytics.model;

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

    public static final String PRODUCTO_VISTO = "PRODUCTO_VISTO";
    public static final String ITEM_AGREGADO = "ITEM_AGREGADO";
    public static final String ITEM_ELIMINADO = "ITEM_ELIMINADO";
    public static final String ENVIO_ACTUALIZADO = "ENVIO_ACTUALIZADO";

    public record ProductoSnapshot(String sku, String nombre, String categoria, Double precio) {
    }

    public record EnvioSnapshot(String metodo, String region) {
    }
}
