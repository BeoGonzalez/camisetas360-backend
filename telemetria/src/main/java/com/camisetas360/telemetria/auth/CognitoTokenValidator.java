package com.camisetas360.telemetria.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Valida el access token de Amazon Cognito con las mismas reglas que los microservicios: firma RS256 (JWKS
 * del user pool), issuer exacto, exp obligatorio, {@code token_use=access}, {@code client_id} permitido,
 * {@code sub} UUID canonico y {@code username}; para el dashboard ademas grupo ADMIN o CATALOG_MANAGER.
 * El usuario es {@code <UUID v3 de "cognito:" + iss>:<sub>}, igual que el {@code usuarioId} del backend.
 */
public class CognitoTokenValidator implements ValidadorToken {

    static final Set<String> GRUPOS = Set.of("ADMIN", "CATALOG_MANAGER");

    private final String issuer;
    private final Set<String> clientIds;
    private final DefaultJWTProcessor<SecurityContext> processor;

    /** Produccion: JWKS {@code <issuer>/.well-known/jwks.json} con cache, cargado en la primera conexion. */
    public static CognitoTokenValidator remoto(String issuer, Collection<String> clientIds) {
        requerido(issuer, "COGNITO_ISSUER_URI");
        try {
            var url = URI.create(issuer + "/.well-known/jwks.json").toURL();
            return new CognitoTokenValidator(issuer, clientIds, JWKSourceBuilder.create(url).build());
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("COGNITO_ISSUER_URI invalido", e);
        }
    }

    public CognitoTokenValidator(String issuer, Collection<String> clientIds, JWKSource<SecurityContext> claves) {
        requerido(issuer, "COGNITO_ISSUER_URI");
        if (issuer.endsWith("/")) throw new IllegalStateException("COGNITO_ISSUER_URI no debe terminar en '/'");
        if (clientIds == null || clientIds.isEmpty()) throw new IllegalStateException("Falta la variable de entorno COGNITO_CLIENT_IDS");
        this.issuer = issuer;
        this.clientIds = Set.copyOf(clientIds);
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, claves));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                null,
                new JWTClaimsSet.Builder().issuer(issuer).claim("token_use", "access").build(),
                Set.of("exp", "sub", "client_id", "username"),
                null));
    }

    public String issuer() {
        return issuer;
    }

    @Override
    public String validar(String token) throws TokenInvalidoException {
        if (token == null || token.isBlank()) throw new TokenInvalidoException("Falta el token");
        JWTClaimsSet claims;
        try {
            claims = processor.process(token.trim(), null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new TokenInvalidoException("Token invalido", e);
        } catch (RuntimeException e) {
            throw new TokenInvalidoException("No se pudo validar el token", e); // fail closed
        }
        if (!(claims.getClaim("client_id") instanceof String cliente) || !clientIds.contains(cliente)) {
            throw new TokenInvalidoException("client_id no autorizado");
        }
        UUID sub = uuidCanonico(claims.getClaim("sub"));
        if (!tieneGrupo(claims.getClaim("cognito:groups"))) {
            throw new TokenInvalidoException("Permisos insuficientes para el dashboard");
        }
        UUID tenant = UUID.nameUUIDFromBytes(("cognito:" + issuer).getBytes(StandardCharsets.UTF_8));
        return tenant + ":" + sub;
    }

    private static boolean tieneGrupo(Object claim) throws TokenInvalidoException {
        if (claim == null) return false;
        if (claim instanceof String grupo) return GRUPOS.contains(grupo);
        if (claim instanceof List<?> grupos && grupos.stream().allMatch(String.class::isInstance)) {
            return grupos.stream().anyMatch(GRUPOS::contains);
        }
        throw new TokenInvalidoException("Claim cognito:groups con formato invalido");
    }

    private static UUID uuidCanonico(Object valor) throws TokenInvalidoException {
        try {
            if (!(valor instanceof String texto)) throw new IllegalArgumentException();
            UUID id = UUID.fromString(texto);
            if (!id.toString().equalsIgnoreCase(texto)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) {
            throw new TokenInvalidoException("El token debe contener un UUID canonico en sub");
        }
    }

    private static void requerido(String valor, String nombre) {
        if (valor == null || valor.isBlank()) throw new IllegalStateException("Falta la variable de entorno " + nombre);
    }
}
