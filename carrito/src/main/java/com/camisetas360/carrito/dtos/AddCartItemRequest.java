package com.camisetas360.carrito.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Datos del producto tal como los muestra el catalogo al agregarlo al carrito. */
public record AddCartItemRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String sku,
        @NotBlank @Size(max = 255) String nombre,
        @Size(max = 255) String categoria,
        @NotNull @Positive Double precioUnitario,
        @NotNull @Min(1) @Max(99) Integer cantidad
) {
}
