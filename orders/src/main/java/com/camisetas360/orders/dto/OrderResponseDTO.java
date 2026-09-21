package com.camisetas360.orders.dto;

import com.camisetas360.orders.model.OrderStatus;

import java.time.Instant;
import java.util.List;

public record OrderResponseDTO(
        Long orderId,
        String userEmail,
        Double totalAmount,
        OrderStatus status,
        Instant createdAt,
        List<OrderItemResponseDTO> items
) {
}