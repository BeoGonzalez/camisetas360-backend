package com.camisetas360.orders.dto;

public record OrderItemResponseDTO(
        String sku,
        Integer quantity,
        Double unitPrice
) {
}