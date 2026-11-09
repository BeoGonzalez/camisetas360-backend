package com.camisetas360.pago.port;

public record IniciarPagoResult(
    String token,
    String urlRedireccion
) {}
