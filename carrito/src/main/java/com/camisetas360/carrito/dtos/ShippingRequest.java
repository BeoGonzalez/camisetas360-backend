package com.camisetas360.carrito.dtos;

import com.camisetas360.carrito.model.ShippingMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShippingRequest(
        @NotNull ShippingMethod metodo,
        @NotBlank @Size(max = 100) String region,
        @NotBlank @Size(max = 100) String comuna
) {
}
