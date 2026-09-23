package com.camisetas360.notifications.event;

public record OrderItemEvent(
        String sku,
        Integer quantity,
        Double unitPrice
) {
}