package com.camisetas360.carrito.messaging;

public final class KafkaTopics {

    public static final String ITEM_AGREGADO = "carrito.item-agregado";
    public static final String ITEM_ELIMINADO = "carrito.item-eliminado";
    public static final String ENVIO_ACTUALIZADO = "carrito.envio-actualizado";

    private KafkaTopics() {
    }
}
