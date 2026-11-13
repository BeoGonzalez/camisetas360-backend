package com.camisetas360.analytics.model;

/** Producto conocido por analitica, construido a partir de los eventos recibidos. */
public record ProductoInfo(String sku, String nombre, String categoria, Double precio, long vistas) {
}
