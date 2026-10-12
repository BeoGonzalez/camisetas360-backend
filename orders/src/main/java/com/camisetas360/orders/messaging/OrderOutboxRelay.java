package com.camisetas360.orders.messaging;

import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Component
public class OrderOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OrderOutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final OrderEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public OrderOutboxRelay(JdbcTemplate jdbc, OrderEventPublisher publisher, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:5000}")
    public void relay() {
        try {
            // Bounded work per poll, also safe with multiple orders ECS tasks.
            for (int i = 0; i < 20; i++) {
                if (!Boolean.TRUE.equals(transaction.execute(status -> sendNext()))) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Outbox poll failed; durable events retained", failure);
        }
    }

    private boolean sendNext() {
        var rows = jdbc.query("""
                SELECT event_id, payload::text, attempts FROM order_event_outbox
                WHERE published_at IS NULL AND attempts < 10 AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
        if (rows.isEmpty()) return false;
        var pending = rows.getFirst();
        try {
            publisher.sendConfirmed(json.readValue(pending.payload(), OrderCreatedEvent.class));
            jdbc.update("UPDATE order_event_outbox SET published_at = CURRENT_TIMESTAMP WHERE event_id = ?",
                    pending.id());
            log.info("Order event confirmed eventId={}", pending.id());
            return true;
        } catch (RuntimeException failure) {
            int attempts = pending.attempts() + 1;
            long delay = Math.min(300, 5L << Math.min(attempts - 1, 6));
            jdbc.update("""
                    UPDATE order_event_outbox SET attempts = ?,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                    WHERE event_id = ?
                    """, attempts, delay, pending.id());
            log.warn("Order publish failed eventId={}, attempt={}, parked={}",
                    pending.id(), attempts, attempts >= 10, failure);
            return false;
        }
    }

    private record Pending(UUID id, String payload, int attempts) {}
}
