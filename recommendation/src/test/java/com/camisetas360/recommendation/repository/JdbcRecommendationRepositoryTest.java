package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Recommendation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcRecommendationRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RecommendationRepository repository = new JdbcRecommendationRepository(jdbcTemplate);

    @Test
    @DisplayName("Should query and map saved recommendation")
    @SuppressWarnings("unchecked")
    void shouldSaveRecommendation() {
        Recommendation expected = new Recommendation(
                1L, 42L, "fan@camisetas360.test",
                "[{\"name\":\"CAM-01\"}]", "Recomendación personalizada",
                Instant.now()
        );

        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(42L), eq("fan@camisetas360.test"), eq("[{\"name\":\"CAM-01\"}]"), eq("Recomendación personalizada")))
                .thenReturn(expected);

        Recommendation result = repository.save(42L, "fan@camisetas360.test", "[{\"name\":\"CAM-01\"}]", "Recomendación personalizada");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.orderId()).isEqualTo(42L);
        assertThat(result.customerEmail()).isEqualTo("fan@camisetas360.test");
        assertThat(result.explanation()).isEqualTo("Recomendación personalizada");
    }

    @Test
    @DisplayName("Should find recommendations by order id")
    @SuppressWarnings("unchecked")
    void shouldFindByOrderId() {
        Recommendation expected = new Recommendation(
                1L, 42L, "fan@camisetas360.test",
                "[{\"name\":\"CAM-01\"}]", "Recomendación personalizada",
                Instant.now()
        );

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(42L)))
                .thenReturn(List.of(expected));

        List<Recommendation> results = repository.findByOrderId(42L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).orderId()).isEqualTo(42L);
    }
}

