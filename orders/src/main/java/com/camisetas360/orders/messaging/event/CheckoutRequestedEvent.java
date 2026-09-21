package com.camisetas360.orders.messaging.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutRequestedEvent(
        UUID eventId,
        String userEmail,
        List<CheckoutItemEvent> items,
        Instant occurredAt
) {
}