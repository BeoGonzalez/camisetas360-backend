package com.camisetas360.pago.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record IniciarPagoRequest(
        @NotNull(message = "El id de orden es obligatorio")
        UUID ordenId,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "1.0", message = "El monto debe ser mayor a 0")
        BigDecimal monto,

        @NotBlank(message = "El número de tarjeta es obligatorio")
        String numeroTarjeta,

        // Opcional: las tarjetas de débito Redcompra no tienen CVV.
        @Pattern(regexp = "\\d{3,4}", message = "El CVV debe tener 3 o 4 dígitos")
        String cvv,

        @NotNull(message = "El mes de vencimiento es obligatorio")
        @Min(value = 1, message = "Mes debe ser entre 1 y 12")
        @Max(value = 12, message = "Mes debe ser entre 1 y 12")
        Integer mesVencimiento,

        @NotNull(message = "El año de vencimiento es obligatorio")
        @Min(value = 2024, message = "Año inválido")
        Integer anioVencimiento,

        String nombreTitular
) {}
