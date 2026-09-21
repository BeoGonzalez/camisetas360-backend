package com.camisetas360.orders.messaging.event;

public record OrderItemEvent(
        String sku,
        Integer quantity,
        Double unitPrice
) {
}