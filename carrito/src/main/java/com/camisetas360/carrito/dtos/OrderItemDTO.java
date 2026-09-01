package com.camisetas360.carrito.dtos;

public record OrderItemDTO(
        String sku,
        Integer quantity,
        Double unitPrice
) {}
