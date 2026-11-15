package com.camisetas360.orders.messaging;

import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.ArgumentMatchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OrderOutboxRelayTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderEventPublisher publisher = mock(OrderEventPublisher.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    /** Simula N filas pendientes en order_event_outbox, entregadas de a una (FOR UPDATE SKIP LOCKED LIMIT 1). */
    private void pendientes(int total) throws Exception {
        String payload = JsonMapper.builder().build().writeValueAsString(new OrderCreatedEvent(
                UUID.randomUUID(), 1L, "buyer@example.test", 10.0, List.of(), Instant.parse("2026-11-11T00:00:00Z")));
        var restantes = new AtomicInteger(total);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(inv -> {
            if (restantes.getAndDecrement() <= 0) return List.of();
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getString(2)).thenReturn(payload);
            when(rs.getInt(3)).thenReturn(0);
            RowMapper<?> mapper = inv.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    @Test
    void unCicloVaciaElBacklogEnVezDeDetenerseEn20() throws Exception {
        pendientes(50);

        new OrderOutboxRelay(jdbc, publisher, manager, 500).relay();

        verify(publisher, times(50)).sendConfirmed(any());
    }

    @Test
    void unCicloRespetaElTopeDeSeguridad() throws Exception {
        pendientes(50);

        new OrderOutboxRelay(jdbc, publisher, manager, 30).relay();

        verify(publisher, times(30)).sendConfirmed(any());
    }

    @Test
    void unFalloDePublicacionCortaElCicloSinPerderElEvento() throws Exception {
        pendientes(5);
        doNothing().doThrow(new org.springframework.amqp.AmqpException("broker down")).when(publisher).sendConfirmed(any());

        new OrderOutboxRelay(jdbc, publisher, manager, 500).relay();

        verify(publisher, times(2)).sendConfirmed(any());
        // El evento fallido se reprograma con backoff (attempts + next_attempt_at), no se marca publicado.
        verify(jdbc, times(1)).update(contains("SET published_at"), any(Object[].class));
        verify(jdbc, times(1)).update(contains("SET attempts"), any(Object[].class));
    }
}
