package com.camisetas360.carrito.dtos;

import java.util.UUID;

public record CheckoutResponseDTO(
        UUID requestId,
        String userEmail,
        Double totalAmount,
        String status
) {
}