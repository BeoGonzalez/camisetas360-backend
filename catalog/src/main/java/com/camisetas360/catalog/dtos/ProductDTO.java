package com.camisetas360.catalog.dtos;

/**
 * DTO (Data Transfer Object) inmutable para exponer los datos del producto
 * de forma segura sin acoplar la capa de presentación a la capa de persistencia.
 */
public record ProductDTO(
        String sku,
        String name,
        String category,
        Double price,
        Integer stock,
        String description
) {}