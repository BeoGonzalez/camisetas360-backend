package com.camisetas360.pago.exception;

import java.util.UUID;

public class FraudeDetectadoException extends RuntimeException {

    private final UUID ordenId;
    private final UUID pagoId;
    private final String tarjetaHash;
    private final String tarjetaUltimos4;
    private final int intentos;
    private final int ventanaSegundos;

    public FraudeDetectadoException(String message, UUID ordenId, UUID pagoId,
                                    String tarjetaHash, String tarjetaUltimos4,
                                    int intentos, int ventanaSegundos) {
        super(message);
        this.ordenId = ordenId;
        this.pagoId = pagoId;
        this.tarjetaHash = tarjetaHash;
        this.tarjetaUltimos4 = tarjetaUltimos4;
        this.intentos = intentos;
        this.ventanaSegundos = ventanaSegundos;
    }

    public UUID getOrdenId() {
        return ordenId;
    }

    public UUID getPagoId() {
        return pagoId;
    }

    public String getTarjetaHash() {
        return tarjetaHash;
    }

    public String getTarjetaUltimos4() {
        return tarjetaUltimos4;
    }

    public int getIntentos() {
        return intentos;
    }

    public int getVentanaSegundos() {
        return ventanaSegundos;
    }
}
