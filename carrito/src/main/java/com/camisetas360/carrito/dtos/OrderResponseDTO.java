package com.camisetas360.carrito.dtos;

public record OrderResponseDTO(
        Long orderId,
        String userEmail,
        Double totalAmount,
        String status
) {}
