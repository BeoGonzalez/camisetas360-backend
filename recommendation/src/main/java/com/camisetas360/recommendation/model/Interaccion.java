package com.camisetas360.recommendation.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Interaccion de un usuario con un producto.
 *
 * @param eventId   id del evento de origen; deduplica reentregas
 * @param usuarioId identidad {@code <tid>:<oid>} en minusculas
 * @param producto  ficha del producto (al menos el sku)
 * @param origen    servicio que produjo el evento (catalog, carrito, orders)
 */
public record Interaccion(UUID eventId, String usuarioId, ProductoFicha producto, Accion accion,
                          String origen, Instant ocurridoEn) {

    public Interaccion {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(producto, "producto");
        Objects.requireNonNull(producto.sku(), "producto.sku");
        Objects.requireNonNull(accion, "accion");
        Objects.requireNonNull(origen, "origen");
        Objects.requireNonNull(ocurridoEn, "ocurridoEn");
    }

    public String sku() {
        return producto.sku();
    }
}
