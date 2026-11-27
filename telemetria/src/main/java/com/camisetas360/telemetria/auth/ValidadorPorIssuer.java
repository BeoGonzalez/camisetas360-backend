package com.camisetas360.telemetria.auth;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Map;

/**
 * Enruta el token a su validador por el claim {@code iss} exacto (Entra ID y/o Cognito), como
 * {@code SecurityConfig.managers} en los microservicios. El {@code iss} se lee sin verificar solo para
 * elegir el validador, que despues comprueba firma e issuer. Un issuer desconocido se rechaza.
 */
public class ValidadorPorIssuer implements ValidadorToken {

    private final Map<String, ValidadorToken> porIssuer;

    public ValidadorPorIssuer(Map<String, ValidadorToken> porIssuer) {
        if (porIssuer.isEmpty()) {
            throw new IllegalStateException("Configura ENTRA_ISSUER_URI/ENTRA_AUDIENCE o COGNITO_ISSUER_URI/COGNITO_CLIENT_IDS");
        }
        this.porIssuer = Map.copyOf(porIssuer);
    }

    @Override
    public String validar(String token) throws TokenInvalidoException {
        if (token == null || token.isBlank()) throw new TokenInvalidoException("Falta el token");
        String issuer;
        try {
            issuer = JWTParser.parse(token.trim()).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new TokenInvalidoException("Token invalido", e);
        }
        ValidadorToken validador = issuer == null ? null : porIssuer.get(issuer);
        if (validador == null) throw new TokenInvalidoException("Issuer no confiable");
        return validador.validar(token);
    }
}
