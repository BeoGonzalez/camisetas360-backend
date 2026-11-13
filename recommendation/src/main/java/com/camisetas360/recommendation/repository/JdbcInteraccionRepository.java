package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.model.Recomendacion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

@Repository
public class JdbcInteraccionRepository implements InteraccionRepository {

    private static final String INSERTAR_INTERACCION = """
            INSERT INTO interacciones (event_id, usuario_id, sku, accion, peso, origen, ocurrido_en)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String UPSERT_PRODUCTO = """
            INSERT INTO productos_conocidos (sku, nombre, categoria, precio, actualizado_en)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (sku) DO UPDATE SET
                nombre = COALESCE(EXCLUDED.nombre, productos_conocidos.nombre),
                categoria = COALESCE(EXCLUDED.categoria, productos_conocidos.categoria),
                precio = COALESCE(EXCLUDED.precio, productos_conocidos.precio),
                actualizado_en = CURRENT_TIMESTAMP
            """;

    // Serializa el recalculo por usuario: los eventos de Kafka y de RabbitMQ del mismo
    // usuario pueden llegar en paralelo y el borrado + insercion debe ser atomico.
    private static final String BLOQUEAR_USUARIO = "SELECT pg_advisory_xact_lock(hashtext(?))";

    private static final String BORRAR_TOP = "DELETE FROM recomendaciones WHERE usuario_id = ?";

    // Desempate determinista: a igual puntaje gana la interaccion mas reciente y luego el sku.
    private static final String INSERTAR_TOP = """
            INSERT INTO recomendaciones (usuario_id, posicion, sku, puntaje, actualizado_en)
            SELECT usuario_id,
                   ROW_NUMBER() OVER (ORDER BY SUM(peso) DESC, MAX(ocurrido_en) DESC, sku),
                   sku, SUM(peso), CURRENT_TIMESTAMP
            FROM interacciones
            WHERE usuario_id = ?
            GROUP BY usuario_id, sku
            ORDER BY SUM(peso) DESC, MAX(ocurrido_en) DESC, sku
            LIMIT 5
            """;

    private static final String LEER_TOP = """
            SELECT r.posicion, r.sku, r.puntaje, p.nombre, p.categoria, p.precio
            FROM recomendaciones r
            LEFT JOIN productos_conocidos p ON p.sku = r.sku
            WHERE r.usuario_id = ?
            ORDER BY r.posicion
            """;

    private final JdbcTemplate jdbc;

    public JdbcInteraccionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean guardarSiNoExiste(Interaccion interaccion) {
        return jdbc.update(INSERTAR_INTERACCION,
                interaccion.eventId(),
                interaccion.usuarioId(),
                interaccion.sku(),
                interaccion.accion().name(),
                interaccion.accion().peso(),
                interaccion.origen(),
                Timestamp.from(interaccion.ocurridoEn())) == 1;
    }

    @Override
    public void actualizarProducto(ProductoFicha producto) {
        jdbc.update(UPSERT_PRODUCTO, producto.sku(), producto.nombre(), producto.categoria(),
                producto.precio() == null ? null : BigDecimal.valueOf(producto.precio()));
    }

    @Override
    public void bloquearUsuario(String usuarioId) {
        jdbc.query(BLOQUEAR_USUARIO, rs -> null, usuarioId);
    }

    @Override
    public void recalcularTop5(String usuarioId) {
        jdbc.update(BORRAR_TOP, usuarioId);
        jdbc.update(INSERTAR_TOP, usuarioId);
    }

    @Override
    public List<Recomendacion> top5(String usuarioId) {
        return jdbc.query(LEER_TOP, (rs, row) -> {
            BigDecimal precio = rs.getBigDecimal("precio");
            return new Recomendacion(
                    rs.getInt("posicion"),
                    rs.getString("sku"),
                    rs.getInt("puntaje"),
                    rs.getString("nombre"),
                    rs.getString("categoria"),
                    precio == null ? null : precio.doubleValue());
        }, usuarioId);
    }
}
