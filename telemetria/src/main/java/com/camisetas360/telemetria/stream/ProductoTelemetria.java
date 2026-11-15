package com.camisetas360.telemetria.stream;

/**
 * Estado actual de un producto en la tabla {@code camisetas360-analytics-productos}
 * (lo escribe {@code analytics.ProductoRepository}). No contiene datos de usuarios.
 */
public record ProductoTelemetria(
        String sku,
        String nombre,
        String categoria,
        Double precio,
        long vistas,
        long agregados,
        String actualizadoEn) {
}
