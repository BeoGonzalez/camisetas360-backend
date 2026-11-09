package com.camisetas360.pago.port;

import java.math.BigDecimal;
import java.util.UUID;

public record IniciarPagoComando(
    UUID ordenId,
    BigDecimal monto,
    String tarjetaUltimos4,
    String tarjetaMarca
) {}
