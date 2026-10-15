package com.camisetas360.recommendation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/**
 * JDBC implementation of {@link OrderInboxRepository}.
 * Encapsulates SQL query execution and PostgreSQL JSONB casting.
 */
@Repository
public class JdbcOrderInboxRepository implements OrderInboxRepository {

    private static final String INSERT_INBOX_SQL = """
            INSERT INTO recommendation_order_inbox (event_id, order_id, payload)
            VALUES (?, ?, CAST(? AS jsonb)) ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String MARK_PROCESSED_SQL = """
            UPDATE recommendation_order_inbox
            SET processed_at = CURRENT_TIMESTAMP
            WHERE event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrderInboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public boolean saveIfAbsent(UUID eventId, long orderId, String rawPayload) {
        return jdbcTemplate.update(INSERT_INBOX_SQL, eventId, orderId, rawPayload) == 1;
    }

    @Override
    public void markProcessed(UUID eventId) {
        jdbcTemplate.update(MARK_PROCESSED_SQL, eventId);
    }
}
