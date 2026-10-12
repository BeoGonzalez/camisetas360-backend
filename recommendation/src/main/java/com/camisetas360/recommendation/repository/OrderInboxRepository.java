package com.camisetas360.recommendation.repository;

import java.util.UUID;

/**
 * Data access abstraction for persisting order events into the durable inbox table.
 * Supports idempotency via ON CONFLICT DO NOTHING.
 */
public interface OrderInboxRepository {

    /**
     * Persists an event into the recommendation inbox if it does not already exist.
     *
     * @param eventId unique event identifier
     * @param orderId associated order identifier
     * @param rawPayload json payload string
     * @return true if the event was inserted, false if it was a duplicate and ignored
     */
    boolean saveIfAbsent(UUID eventId, long orderId, String rawPayload);
}

