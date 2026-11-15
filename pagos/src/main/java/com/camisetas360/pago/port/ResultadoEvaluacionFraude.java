package com.camisetas360.pago.port;

/**
 * Resultado de evaluar un intento de pago.
 *
 * @param segundosParaReintentar 0 si el intento se permite; si se bloquea, los segundos que faltan
 *                               para que la tarjeta se desbloquee (se envia como header Retry-After).
 */
public record ResultadoEvaluacionFraude(
    boolean permitido,
    int intentosEnVentana,
    int ventanaSegundos,
    String motivo,
    long segundosParaReintentar
) {
    public ResultadoEvaluacionFraude(boolean permitido, int intentosEnVentana, int ventanaSegundos, String motivo) {
        this(permitido, intentosEnVentana, ventanaSegundos, motivo, 0L);
    }
}
