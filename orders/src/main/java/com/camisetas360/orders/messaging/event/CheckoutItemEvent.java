package com.camisetas360.orders.messaging.event;

public record CheckoutItemEvent(
        String sku,
        Integer quantity,
        Double unitPrice
) {
}