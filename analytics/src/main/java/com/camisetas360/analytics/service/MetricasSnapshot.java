package com.camisetas360.analytics.service;

import java.util.List;
import java.util.Map;

/**
 * Metricas analiticas en tiempo real que se envian al dashboard por WebSocket.
 *
 * @param clicsUltimoMinuto  volumen de interacciones en los ultimos 60 segundos
 * @param usuariosActivos    usuarios distintos con actividad en los ultimos 5 minutos
 * @param serieClics         interacciones por minuto de los ultimos 15 minutos (de la mas antigua a la actual)
 * @param productosMasVistos top 5 de productos vistos en la ultima hora
 * @param eventosPorTipo     conteo por tipo de evento en la ultima hora
 * @param alertas            alertas recientes (fraude, navegacion anomala, picos), la mas nueva primero
 */
public record MetricasSnapshot(
        String generadoEn,
        long clicsUltimoMinuto,
        long usuariosActivos,
        List<PuntoSerie> serieClics,
        List<ProductoVisto> productosMasVistos,
        Map<String, Long> eventosPorTipo,
        List<Alerta> alertas
) {
    public record PuntoSerie(String minuto, long clics) {
    }

    public record ProductoVisto(String sku, String nombre, long vistas) {
    }

    public record Alerta(String tipo, String mensaje, String ocurridoEn) {
    }
}
