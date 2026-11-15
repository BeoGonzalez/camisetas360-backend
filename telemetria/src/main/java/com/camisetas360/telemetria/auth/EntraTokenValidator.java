package com.camisetas360.telemetria.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Valida el access token de Microsoft Entra ID con las mismas reglas que el dashboard STOMP de
 * analytics: firma RS256 (JWKS del tenant), issuer exacto, audience, exp obligatorio, tid/oid como
 * UUID canonico, rol ADMIN o CATALOG_MANAGER y scope Catalog.Read.
 */
public class EntraTokenValidator {

    static final Set<String> ROLES = Set.of("ADMIN", "CATALOG_MANAGER");
    static final String SCOPE = "Catalog.Read";

    private final DefaultJWTProcessor<SecurityContext> processor;

    /** Produccion: JWKS remoto con cache, resuelto en la primera conexion (no en el arranque en frio). */
    public static EntraTokenValidator remoto(String issuer, String audience, String jwksUri) {
        requerido(issuer, "ENTRA_ISSUER_URI");
        requerido(audience, "ENTRA_AUDIENCE");
        Supplier<URL> url = () -> {
            try {
                String jwks = jwksUri == null || jwksUri.isBlank() ? descubrirJwks(issuer) : jwksUri;
                return URI.create(jwks).toURL();
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo obtener el JWKS de Entra", e);
            }
        };
        return new EntraTokenValidator(issuer, audience, new JwksPerezoso(url));
    }

    public EntraTokenValidator(String issuer, String audience, JWKSource<SecurityContext> claves) {
        requerido(issuer, "ENTRA_ISSUER_URI");
        requerido(audience, "ENTRA_AUDIENCE");
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, claves));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                Set.of(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("exp", "iat", "tid", "oid"),
                null));
    }

    /** @return el usuario como {@code <tid>:<oid>} en minusculas, igual que en el resto del backend. */
    public String validar(String token) throws TokenInvalidoException {
        if (token == null || token.isBlank()) {
            throw new TokenInvalidoException("Falta el token");
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(token.trim(), null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new TokenInvalidoException("Token invalido", e);
        } catch (RuntimeException e) {
            // Fallo al obtener el JWKS: se rechaza la conexion (fail closed).
            throw new TokenInvalidoException("No se pudo validar el token", e);
        }

        UUID tid = uuidCanonico(claims, "tid");
        UUID oid = uuidCanonico(claims, "oid");
        if (!tieneRol(claims) || !tieneScope(claims)) {
            throw new TokenInvalidoException("Permisos insuficientes para el dashboard");
        }
        return tid + ":" + oid;
    }

    private static boolean tieneRol(JWTClaimsSet claims) throws TokenInvalidoException {
        Object roles = claims.getClaim("roles");
        if (roles == null) {
            return false;
        }
        if (!(roles instanceof Collection<?> valores) || !valores.stream().allMatch(String.class::isInstance)) {
            throw new TokenInvalidoException("Claim roles con formato invalido");
        }
        return valores.stream().anyMatch(ROLES::contains);
    }

    private static boolean tieneScope(JWTClaimsSet claims) throws TokenInvalidoException {
        for (String nombre : List.of("scp", "scope")) {
            Object valor = claims.getClaim(nombre);
            if (valor == null) {
                continue;
            }
            if (valor instanceof String texto) {
                if (Arrays.asList(texto.trim().split("\\s+")).contains(SCOPE)) return true;
            } else if (valor instanceof Collection<?> lista && lista.stream().allMatch(String.class::isInstance)) {
                if (lista.contains(SCOPE)) return true;
            } else {
                throw new TokenInvalidoException("Claim " + nombre + " con formato invalido");
            }
        }
        return false;
    }

    private static UUID uuidCanonico(JWTClaimsSet claims, String nombre) throws TokenInvalidoException {
        try {
            String valor = claims.getStringClaim(nombre);
            UUID id = UUID.fromString(valor);
            if (!id.toString().equalsIgnoreCase(valor)) throw new IllegalArgumentException();
            return id;
        } catch (ParseException | IllegalArgumentException | NullPointerException e) {
            throw new TokenInvalidoException("El token debe contener un UUID canonico en " + nombre);
        }
    }

    private static String descubrirJwks(String issuer) throws IOException {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        var conexion = URI.create(base + "/.well-known/openid-configuration").toURL().openConnection();
        conexion.setConnectTimeout(3000);
        conexion.setReadTimeout(3000);
        try (InputStream in = conexion.getInputStream()) {
            var documento = new ObjectMapper().readTree(in);
            if (!issuer.equals(documento.path("issuer").asText())) {
                throw new IOException("El documento OIDC pertenece a otro issuer");
            }
            String jwks = documento.path("jwks_uri").asText("");
            if (!jwks.startsWith("https://")) {
                throw new IOException("jwks_uri invalido en el documento OIDC");
            }
            return jwks;
        }
    }

    private static void requerido(String valor, String nombre) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno " + nombre);
        }
    }

    /** Crea el JWKSource remoto (con cache y rate limit de Nimbus) recien cuando llega el primer token. */
    private static final class JwksPerezoso implements JWKSource<SecurityContext> {
        private final Supplier<URL> url;
        private volatile JWKSource<SecurityContext> delegado;

        JwksPerezoso(Supplier<URL> url) {
            this.url = url;
        }

        @Override
        public List<com.nimbusds.jose.jwk.JWK> get(com.nimbusds.jose.jwk.JWKSelector selector, SecurityContext ctx)
                throws com.nimbusds.jose.KeySourceException {
            JWKSource<SecurityContext> actual = delegado;
            if (actual == null) {
                synchronized (this) {
                    if (delegado == null) {
                        delegado = JWKSourceBuilder.create(url.get()).build();
                    }
                    actual = delegado;
                }
            }
            return actual.get(selector, ctx);
        }
    }
}
