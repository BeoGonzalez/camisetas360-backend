package com.camisetas360.carrito.dtos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderItemDTO(

        @NotBlank
        String sku,

        @NotNull
        @Min(1)
        Integer quantity,

        @NotNull
        @Positive
        Double unitPrice

) {
}