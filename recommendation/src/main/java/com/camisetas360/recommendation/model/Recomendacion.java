package com.camisetas360.recommendation.model;

/** Una posicion del Top 5 de un usuario, enriquecida con la ultima ficha conocida del producto. */
public record Recomendacion(int posicion, String sku, int puntaje, String nombre, String categoria, Double precio) {
}
