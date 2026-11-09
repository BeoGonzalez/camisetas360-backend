package com.camisetas360.pago.port;

public record ResultadoEvaluacionFraude(
    boolean permitido,
    int intentosEnVentana,
    int ventanaSegundos,
    String motivo
) {}
