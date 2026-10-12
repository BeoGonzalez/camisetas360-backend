package com.camisetas360.recommendation.messaging;

/**
 * Ingestion port for durable order events into the recommendation inbox.
 */
public interface OrderEventInbox {

    /**
     * Validates and persists an incoming raw message payload into the inbox.
     *
     * @param body raw byte array from the message broker
     * @return true if inserted as a new event, false if duplicate
     * @throws IllegalArgumentException if the event is malformed or invalid
     */
    boolean accept(byte[] body);
}
