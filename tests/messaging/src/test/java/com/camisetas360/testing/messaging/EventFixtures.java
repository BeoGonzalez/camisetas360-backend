package com.camisetas360.testing.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class EventFixtures {
    static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final Instant OCCURRED_AT = Instant.parse("2026-01-01T12:00:00Z");
    static final String EMAIL = "buyer@example.test";

    static com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent checkout() {
        return new com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent(
                EVENT_ID, EMAIL, List.of(
                new com.camisetas360.carrito.messaging.event.CheckoutItemEvent("CAM-Ñ-東京", 2, 19.5),
                new com.camisetas360.carrito.messaging.event.CheckoutItemEvent("SKU-B", 3, 10.0)), OCCURRED_AT);
    }

    static com.camisetas360.orders.messaging.event.OrderCreatedEvent orderCreated() {
        return new com.camisetas360.orders.messaging.event.OrderCreatedEvent(
                EVENT_ID, 42L, EMAIL, 69.0, List.of(
                new com.camisetas360.orders.messaging.event.OrderItemEvent("CAM-Ñ-東京", 2, 19.5),
                new com.camisetas360.orders.messaging.event.OrderItemEvent("SKU-B", 3, 10.0)), OCCURRED_AT);
    }

    private EventFixtures() {
    }
}

