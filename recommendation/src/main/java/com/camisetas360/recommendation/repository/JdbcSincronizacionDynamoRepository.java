package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.InteraccionPendiente;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Outbox de DynamoDB sobre la tabla interacciones (columnas dynamo_*, migracion V5). */
@Repository
public class JdbcSincronizacionDynamoRepository {

    // SKIP LOCKED: varias tareas ECS pueden sincronizar a la vez sin enviar dos veces la misma fila.
    // Solo bloquea filas de interacciones (nunca las toma registrar), asi que no interfiere con el
    // orden de bloqueos de RecomendacionService.
    private static final String PENDIENTES = """
            SELECT i.event_id, i.usuario_id, i.sku, i.accion, i.peso, i.origen, i.ocurrido_en,
                   i.dynamo_intentos, p.nombre, p.categoria, p.precio
            FROM interacciones i
            LEFT JOIN productos_conocidos p ON p.sku = i.sku
            WHERE i.dynamo_sincronizado_en IS NULL
              AND i.dynamo_proximo_intento <= CURRENT_TIMESTAMP
            ORDER BY i.dynamo_proximo_intento, i.id
            LIMIT ?
            FOR UPDATE OF i SKIP LOCKED
            """;

    private static final String MARCAR = """
            UPDATE interacciones SET dynamo_sincronizado_en = CURRENT_TIMESTAMP WHERE event_id = ?
            """;

    private static final String POSPONER = """
            UPDATE interacciones
            SET dynamo_intentos = ?, dynamo_proximo_intento = CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
            WHERE event_id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcSincronizacionDynamoRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Debe llamarse dentro de una transaccion: las filas quedan bloqueadas hasta el commit. */
    public List<InteraccionPendiente> bloquearPendientes(int limite) {
        return jdbc.query(PENDIENTES, (rs, row) -> {
            BigDecimal precio = rs.getBigDecimal("precio");
            return new InteraccionPendiente(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("usuario_id"),
                    rs.getString("sku"),
                    Accion.valueOf(rs.getString("accion")),
                    rs.getInt("peso"),
                    rs.getString("origen"),
                    rs.getObject("ocurrido_en", OffsetDateTime.class).toInstant(),
                    rs.getInt("dynamo_intentos"),
                    rs.getString("nombre"),
                    rs.getString("categoria"),
                    precio == null ? null : precio.doubleValue());
        }, limite);
    }

    public void marcarSincronizadas(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) return;
        jdbc.batchUpdate(MARCAR, eventIds.stream().map(id -> new Object[]{id}).toList());
    }

    public void posponer(UUID eventId, int intentos, long segundos) {
        jdbc.update(POSPONER, intentos, segundos, eventId);
    }
}
