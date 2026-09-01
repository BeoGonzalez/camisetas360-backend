package com.camisetas360.carrito.dtos;

import java.util.List;

public record OrderRequestDTO(
        List<OrderItemDTO> items
) {}
