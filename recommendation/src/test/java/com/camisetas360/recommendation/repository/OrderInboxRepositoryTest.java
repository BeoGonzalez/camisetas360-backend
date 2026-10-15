package com.camisetas360.recommendation.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderInboxRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final OrderInboxRepository repository = new JdbcOrderInboxRepository(jdbcTemplate);

    @Test
    @DisplayName("Should return true when row is inserted successfully")
    void shouldReturnTrueWhenInserted() {
        UUID eventId = UUID.randomUUID();
        long orderId = 123L;
        String rawPayload = "{\"eventId\":\"" + eventId + "\"}";

        when(jdbcTemplate.update(anyString(), eq(eventId), eq(orderId), eq(rawPayload))).thenReturn(1);

        boolean inserted = repository.saveIfAbsent(eventId, orderId, rawPayload);

        assertThat(inserted).isTrue();
        verify(jdbcTemplate).update(anyString(), eq(eventId), eq(orderId), eq(rawPayload));
    }

    @Test
    @DisplayName("Should return false when conflict occurs and row is not inserted (duplicate)")
    void shouldReturnFalseWhenDuplicateConflict() {
        UUID eventId = UUID.randomUUID();
        long orderId = 123L;
        String rawPayload = "{\"eventId\":\"" + eventId + "\"}";

        when(jdbcTemplate.update(anyString(), eq(eventId), eq(orderId), eq(rawPayload))).thenReturn(0);

        boolean inserted = repository.saveIfAbsent(eventId, orderId, rawPayload);

        assertThat(inserted).isFalse();
    }

    @Test
    @DisplayName("Should execute update query when marking event as processed")
    void shouldExecuteUpdateWhenMarkingProcessed() {
        UUID eventId = UUID.randomUUID();

        repository.markProcessed(eventId);

        verify(jdbcTemplate).update(anyString(), eq(eventId));
    }

    @Test
    @DisplayName("Should propagate database access failure")
    void shouldPropagateDatabaseFailure() {
        UUID eventId = UUID.randomUUID();
        long orderId = 123L;
        String rawPayload = "{}";

        when(jdbcTemplate.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB connection error"));

        assertThatThrownBy(() -> repository.saveIfAbsent(eventId, orderId, rawPayload))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
