package com.camisetas360.catalog.dtos;

import com.camisetas360.catalog.models.KitType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;

/** POST /api/v1/catalog/products. El SKU es la identidad del producto y no se puede cambiar. */
public record ProductCreateRequest(
        @NotBlank @Pattern(regexp = SKU_REGEX) String sku,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100) String team,
        @NotNull KitType kitType,
        @NotBlank @Size(max = 255) String category,
        @Size(max = 100) String league,
        @NotNull @Positive @DecimalMax("100000000") Double price,
        @NotNull @PositiveOrZero @Max(1_000_000) Integer stock,
        @Size(max = 255) String description
) implements ProductFields {
    /** Mismo patrón que aceptan las lecturas por SKU (carrito, pedidos y recomendaciones lo usan). */
    public static final String SKU_REGEX = "[A-Za-z0-9._-]{1,64}";
}
