package com.camisetas360.analytics.security;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Valida el token del frame STOMP CONNECT con las mismas reglas que la API HTTP: el {@code iss} (leido sin
 * verificar, solo para elegir) selecciona el decoder y el conversor de autoridades de Entra o de Cognito,
 * y ese decoder verifica firma, issuer y claims. Un issuer desconocido se rechaza.
 */
public class StompTokenAuthenticator {

    private final Map<String, AuthenticationManager> managers;

    public StompTokenAuthenticator(Map<String, AuthenticationManager> managers) {
        this.managers = Map.copyOf(managers);
    }

    /** Solo Entra ID (tests y despliegues sin Cognito). */
    public static StompTokenAuthenticator soloEntra(String issuer, JwtDecoder decoder) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtAuthoritiesConverter());
        var cognitoApagado = new CognitoProperties(false, null, null, null, null, null, null);
        return new StompTokenAuthenticator(SecurityConfig.managers(issuer, decoder, converter, cognitoApagado));
    }

    public JwtAuthenticationToken autenticar(String token) {
        String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException invalido) {
            throw new InvalidBearerTokenException("Token mal formado");
        }
        AuthenticationManager manager = issuer == null ? null : managers.get(issuer);
        if (manager == null) {
            throw new InvalidBearerTokenException("Issuer no confiable");
        }
        Authentication resultado = manager.authenticate(new BearerTokenAuthenticationToken(token));
        if (!(resultado instanceof JwtAuthenticationToken jwt)) {
            throw new InvalidBearerTokenException("Token no soportado");
        }
        return jwt;
    }
}
