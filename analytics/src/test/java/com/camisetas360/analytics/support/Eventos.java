package com.camisetas360.analytics.support;

import com.camisetas360.analytics.model.InteraccionEvento;

import java.time.Instant;
import java.util.UUID;

/** Fabrica de eventos para tests. */
public final class Eventos {

    public static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222";

    private Eventos() {
    }

    public static InteraccionEvento visto(String sku, String nombre, String categoria, double precio, Instant cuando) {
        return evento(InteraccionEvento.PRODUCTO_VISTO, USUARIO, sku, nombre, categoria, precio, null, cuando);
    }

    public static InteraccionEvento agregado(String sku, String nombre, String categoria, double precio, int cantidad,
                                             Instant cuando) {
        return evento(InteraccionEvento.ITEM_AGREGADO, USUARIO, sku, nombre, categoria, precio, cantidad, cuando);
    }

    public static InteraccionEvento eliminado(String sku, String nombre, String categoria, double precio, int cantidad,
                                              Instant cuando) {
        return evento(InteraccionEvento.ITEM_ELIMINADO, USUARIO, sku, nombre, categoria, precio, cantidad, cuando);
    }

    public static InteraccionEvento evento(String tipo, String usuario, String sku, String nombre, String categoria,
                                           Double precio, Integer cantidad, Instant cuando) {
        return new InteraccionEvento(UUID.randomUUID(), tipo, 1, cuando.toString(),
                InteraccionEvento.PRODUCTO_VISTO.equals(tipo) ? "catalog" : "carrito", usuario,
                new InteraccionEvento.ProductoSnapshot(sku, nombre, categoria, precio), cantidad, null);
    }

    public static InteraccionEvento envio(String usuario, Instant cuando) {
        return new InteraccionEvento(UUID.randomUUID(), InteraccionEvento.ENVIO_ACTUALIZADO, 1, cuando.toString(),
                "carrito", usuario, null, null, new InteraccionEvento.EnvioSnapshot("EXPRESS", "Metropolitana"));
    }
}
