package com.camisetas360.telemetria.auth;

/** El token no es valido o no tiene permisos para el dashboard. El mensaje nunca incluye el token. */
public class TokenInvalidoException extends Exception {

    public TokenInvalidoException(String mensaje) {
        super(mensaje);
    }

    public TokenInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
