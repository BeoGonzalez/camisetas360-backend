package com.camisetas360.recommendation.model;

/** Datos del producto que viajan en los eventos; cualquier campo salvo el sku puede faltar. */
public record ProductoFicha(String sku, String nombre, String categoria, Double precio) {
}
