package com.camisetas360.carrito.messaging.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutRequestedEvent(
        UUID eventId,
        String userEmail,
        List<CheckoutItemEvent> items,
        Instant occurredAt,
        UUID tenantId,
        UUID objectId
) {
    // Legacy wire payloads have unknown ownership; they are never inferred from email.
    public CheckoutRequestedEvent(UUID eventId, String userEmail, List<CheckoutItemEvent> items, Instant occurredAt) {
        this(eventId, userEmail, items, occurredAt, null, null);
    }
}
