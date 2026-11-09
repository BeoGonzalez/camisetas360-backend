package com.camisetas360.pago.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record IniciarPagoResponse(
        UUID pagoId,
        UUID ordenId,
        BigDecimal monto,
        String estado,
        String token,
        String urlRedireccion,
        String tarjetaUltimos4,
        String tarjetaMarca
) {}
