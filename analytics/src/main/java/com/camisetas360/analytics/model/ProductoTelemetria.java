package com.camisetas360.analytics.model;

/**
 * Estado completo de un producto en la tabla productos tras una actualizacion. Es lo mismo que el
 * WebSocket de telemetria recibe por DynamoDB Streams (Lambda stream, {@code telemetria.ProductoTelemetria})
 * y lo que se publica en Kafka ({@code telemetria.productos-actualizados}). No contiene datos de usuarios.
 */
public record ProductoTelemetria(
        String sku,
        String nombre,
        String categoria,
        Double precio,
        long vistas,
        long agregados,
        String actualizadoEn) {

    public ProductoInfo info() {
        return new ProductoInfo(sku, nombre, categoria, precio, vistas);
    }
}
