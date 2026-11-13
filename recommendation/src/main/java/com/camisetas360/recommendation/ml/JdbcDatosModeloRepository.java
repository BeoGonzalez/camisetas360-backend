package com.camisetas360.recommendation.ml;

import com.camisetas360.recommendation.model.ProductoFicha;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Datos de entrenamiento: las mismas interacciones que se copian a DynamoDB (UserInteractions),
 * agregadas por usuario y producto directamente en PostgreSQL, la fuente de verdad del servicio.
 */
@Repository
public class JdbcDatosModeloRepository {

    /** Peso acumulado por usuario y producto (vista 1, carrito 2, compra 3) y si ya lo compro. */
    public record Par(String usuarioId, String sku, double peso, boolean comprado) {
    }

    private static final String PARES = """
            SELECT usuario_id, sku, SUM(peso) AS peso, BOOL_OR(accion = 'COMPRA') AS comprado
            FROM interacciones
            GROUP BY usuario_id, sku
            ORDER BY usuario_id, sku
            LIMIT ?
            """;

    private static final String PRODUCTOS = "SELECT sku, nombre, categoria, precio FROM productos_conocidos";

    private final JdbcTemplate jdbc;

    public JdbcDatosModeloRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public List<Par> pares(int maximo) {
        return jdbc.query(PARES, (rs, row) -> new Par(
                rs.getString("usuario_id"), rs.getString("sku"), rs.getDouble("peso"), rs.getBoolean("comprado")), maximo);
    }

    public Map<String, ProductoFicha> productos() {
        Map<String, ProductoFicha> productos = new HashMap<>();
        jdbc.query(PRODUCTOS, rs -> {
            BigDecimal precio = rs.getBigDecimal("precio");
            productos.put(rs.getString("sku"), new ProductoFicha(rs.getString("sku"), rs.getString("nombre"),
                    rs.getString("categoria"), precio == null ? null : precio.doubleValue()));
        });
        return productos;
    }
}
