package com.camisetas360.carrito.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OrderRequestDTO(

        @NotEmpty
        List<@Valid OrderItemDTO> items

) {
}