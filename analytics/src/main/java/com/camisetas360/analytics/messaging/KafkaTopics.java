package com.camisetas360.analytics.messaging;

public final class KafkaTopics {

    public static final String PRODUCTO_VISTO = "catalogo.producto-visto";
    public static final String ITEM_AGREGADO = "carrito.item-agregado";
    public static final String ITEM_ELIMINADO = "carrito.item-eliminado";
    public static final String ENVIO_ACTUALIZADO = "carrito.envio-actualizado";
    public static final String PAGO_FRAUDE_DETECTADO = "pago.fraude-detectado";
    /** Lo publica analytics: estado de cada producto actualizado (lo mismo que recibe el WebSocket). */
    public static final String TELEMETRIA_PRODUCTOS = "telemetria.productos-actualizados";

    private KafkaTopics() {
    }
}
