package com.camisetas360.carrito.service;

/** Datos vigentes de un producto segun el catalogo. */
public record ProductoCatalogo(String sku, String nombre, String categoria, double precio) {
}
