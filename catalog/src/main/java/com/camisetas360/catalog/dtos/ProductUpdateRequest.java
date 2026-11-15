package com.camisetas360.catalog.dtos;

import com.camisetas360.catalog.models.KitType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** PUT /api/v1/catalog/products/{sku}: reemplaza todos los datos editables (el SKU va en la ruta). */
public record ProductUpdateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100) String team,
        @NotNull KitType kitType,
        @NotBlank @Size(max = 255) String category,
        @Size(max = 100) String league,
        @NotNull @Positive @DecimalMax("100000000") Double price,
        @NotNull @PositiveOrZero @Max(1_000_000) Integer stock,
        @Size(max = 255) String description
) implements ProductFields {
}
