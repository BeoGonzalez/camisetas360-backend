package com.camisetas360.recommendation.parser;

import java.util.UUID;

/**
 * Immutable record representing the validated, parsed order event contract.
 */
public record ParsedOrderEvent(
        UUID eventId,
        long orderId,
        int itemCount
) {
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
    }
}

