package com.camisetas360.carrito.messaging.event;

public record CheckoutItemEvent(
        String sku,
        Integer quantity,
        Double unitPrice
) {
}