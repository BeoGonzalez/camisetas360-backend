package com.camisetas360.recommendation.parser;

import java.util.List;
import java.util.UUID;

/**
 * Immutable record representing the validated, parsed order event contract.
 */
public record ParsedOrderEvent(
        UUID eventId,
        long orderId,
        int itemCount,
        String userEmail,
        List<String> items
) {
    public ParsedOrderEvent(UUID eventId, long orderId, int itemCount) {
        this(eventId, orderId, itemCount, null, List.of());
    }

    public ParsedOrderEvent {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId cannot be null");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (itemCount <= 0) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        items = items != null ? List.copyOf(items) : List.of();
    }
}

