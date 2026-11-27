package com.camisetas360.telemetria.auth;

/** Valida el access token de {@code $connect} y devuelve el usuario ({@code <tenant>:<objeto>}). */
public interface ValidadorToken {
    String validar(String token) throws TokenInvalidoException;
}
