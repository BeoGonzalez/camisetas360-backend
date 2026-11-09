package com.camisetas360.pago.port;

import java.util.UUID;

public interface FraudeDetector {
    ResultadoEvaluacionFraude evaluarTransaccion(String tarjetaHash, UUID ordenId);
    void reset();
}
