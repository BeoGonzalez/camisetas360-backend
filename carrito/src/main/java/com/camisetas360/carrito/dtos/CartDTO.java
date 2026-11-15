package com.camisetas360.carrito.dtos;

import java.util.List;

public record CartDTO(
        List<Item> items,
        Shipping envio,
        double subtotal,
        double costoEnvio,
        double total
) {
    public record Item(String sku, String nombre, String categoria, double precioUnitario, int cantidad, double subtotal) {
    }

    public record Shipping(String metodo, String region, String comuna, double costo) {
    }
}
