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
        Instant occurredAt,
        UUID tenantId,
        UUID objectId
) {
    // Legacy wire payloads have unknown ownership; they are never inferred from email.
    public OrderCreatedEvent(UUID eventId, Long orderId, String userEmail, Double totalAmount, List<OrderItemEvent> items, Instant occurredAt) {
        this(eventId, orderId, userEmail, totalAmount, items, occurredAt, null, null);
    }
}
