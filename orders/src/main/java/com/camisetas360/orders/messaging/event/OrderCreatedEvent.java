package com.camisetas360.orders.messaging.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        Long orderId,
        String userEmail,
        Double totalAmount,
        List<OrderItemEvent> items,
        Instant occurredAt
) {
}