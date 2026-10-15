package com.camisetas360.recommendation.model;

import java.time.Instant;

/**
 * Immutable domain model representing a generated AI recommendation for an order.
 */
public record Recommendation(
        Long id,
        long orderId,
        String customerEmail,
        String recommendedItems,
        String explanation,
        Instant createdAt
) {
    public Recommendation {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (recommendedItems == null || recommendedItems.isBlank()) {
            throw new IllegalArgumentException("recommendedItems cannot be null or blank");
        }
    }
}

