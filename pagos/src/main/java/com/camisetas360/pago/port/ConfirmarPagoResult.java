package com.camisetas360.pago.port;

public record ConfirmarPagoResult(
    boolean autorizada,
    String codigoAutorizacion,
    String responseCode,
    String motivo
) {}
