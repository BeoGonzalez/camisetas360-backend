package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Recommendation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * JDBC implementation of {@link RecommendationRepository}.
 * Handles PostgreSQL JSONB casting and mapping to {@link Recommendation}.
 */
@Repository
public class JdbcRecommendationRepository implements RecommendationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO recommendations (order_id, customer_email, recommended_items, explanation)
            VALUES (?, ?, CAST(? AS jsonb), ?)
            RETURNING id, order_id, customer_email, CAST(recommended_items AS text) AS recommended_items, explanation, created_at
            """;

    private static final String FIND_BY_ORDER_ID_SQL = """
            SELECT id, order_id, customer_email, CAST(recommended_items AS text) AS recommended_items, explanation, created_at
            FROM recommendations
            WHERE order_id = ?
            ORDER BY created_at DESC
            """;

    private static final RowMapper<Recommendation> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new Recommendation(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("customer_email"),
                rs.getString("recommended_items"),
                rs.getString("explanation"),
                createdAt != null ? createdAt.toInstant() : null
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcRecommendationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    public Recommendation save(long orderId, String customerEmail, String recommendedItemsJson, String explanation) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                ROW_MAPPER,
                orderId,
                customerEmail,
                recommendedItemsJson,
                explanation
        );
    }

    @Override
    public List<Recommendation> findByOrderId(long orderId) {
        return jdbcTemplate.query(FIND_BY_ORDER_ID_SQL, ROW_MAPPER, orderId);
    }
}

