package com.camisetas360.pago.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmarPagoRequest(
        @NotBlank(message = "El token de transacción es obligatorio")
        String token
) {}
