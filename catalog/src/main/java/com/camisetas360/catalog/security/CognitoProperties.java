package com.camisetas360.catalog.security;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Confianza en access tokens de Amazon Cognito para administrar el catalogo (segundo issuer, ademas
 * de Entra ID). Apagado por defecto; con {@code enabled=true} una configuracion incompleta o insegura
 * hace fallar el arranque.
 *
 * <p>Solo se aceptan tokens del user pool ({@code issuer-uri}) y de los app clients listados
 * ({@code client-ids}). Los roles salen de {@code cognito:groups} filtrados por {@code allowed-groups}.
 */
@ConfigurationProperties(prefix = "app.security.cognito")
public record CognitoProperties(
        boolean enabled,
        String issuerUri,
        String jwkSetUri,
        List<String> clientIds,
        List<String> allowedGroups) {

    static final String PREFIX = "app.security.cognito.";
    static final List<String> DEFAULT_GROUPS = List.of("CATALOG_MANAGER", "ADMIN");

    public CognitoProperties {
        issuerUri = blankToNull(issuerUri);
        jwkSetUri = blankToNull(jwkSetUri);
        clientIds = clean(clientIds);
        allowedGroups = allowedGroups == null ? DEFAULT_GROUPS : clean(allowedGroups);
        if (enabled) {
            require(issuerUri != null, "issuer-uri es obligatorio cuando enabled=true (COGNITO_ISSUER_URI)");
            requireSafeUrl("issuer-uri", issuerUri);
            require(!issuerUri.endsWith("/"), "issuer-uri no debe terminar en '/': se compara de forma exacta con 'iss'");
            jwkSetUri = jwkSetUri == null ? issuerUri + "/.well-known/jwks.json" : jwkSetUri;
            requireSafeUrl("jwk-set-uri", jwkSetUri);
            require(!clientIds.isEmpty(), "client-ids es obligatorio cuando enabled=true (COGNITO_CLIENT_IDS)");
            require(!allowedGroups.isEmpty(), "allowed-groups no puede estar vacio");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(PREFIX + message);
    }

    /** HTTPS obligatorio; HTTP solo en loopback (tests con JWKS local). */
    private static void requireSafeUrl(String property, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(PREFIX + property + " no es una URL valida", exception);
        }
        String host = uri.getHost();
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) && host != null;
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
        require(https || loopback, property + " debe usar https (http solo se admite en loopback)");
        require(uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                property + " no debe incluir credenciales, query ni fragmento");
    }
}
