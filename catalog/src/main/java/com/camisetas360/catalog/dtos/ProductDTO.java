package com.camisetas360.catalog.dtos;

import com.camisetas360.catalog.models.Product;

/**
 * DTO (Data Transfer Object) inmutable para exponer los datos del producto
 * de forma segura sin acoplar la capa de presentación a la capa de persistencia.
 *
 * @param team         equipo (null en productos sin club)
 * @param kitType      LOCAL, VISITANTE o ALTERNATIVA (null si no aplica)
 * @param imageVersion epoch ms del último cambio de foto; null = sin foto. La foto se lee en
 *                     GET /api/v1/catalog/products/{sku}/imagen.
 */
public record ProductDTO(
        String sku,
        String name,
        String category,
        Double price,
        Integer stock,
        String description,
        String league,
        String team,
        String kitType,
        Long imageVersion
) {
    /** Producto sin equipo, tipo ni foto. */
    public ProductDTO(String sku, String name, String category, Double price, Integer stock,
                      String description, String league) {
        this(sku, name, category, price, stock, description, league, null, null, null);
    }

    public static ProductDTO from(Product product) {
        return new ProductDTO(
                product.getSku(),
                product.getName(),
                product.getCategory(),
                product.getPrice(),
                product.getStock(),
                product.getDescription(),
                product.getLeague(),
                product.getTeam(),
                product.getKitType() == null ? null : product.getKitType().name(),
                product.getImageUpdatedAt() == null ? null : product.getImageUpdatedAt().toEpochMilli());
    }
}
