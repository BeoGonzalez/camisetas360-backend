package com.camisetas360.analytics.security;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Confianza en access tokens de Amazon Cognito (segundo issuer, ademas de Entra ID): los clientes reciben recomendaciones y ADMIN/CATALOG_MANAGER ven el dashboard.
 * Apagado por defecto; con {@code enabled=true} una configuracion incompleta o insegura hace fallar el
 * arranque.
 *
 * <p>Solo se aceptan tokens del user pool ({@code issuer-uri}) y de los app clients listados
 * ({@code client-ids}). El rol sale de {@code cognito:groups} filtrado por {@code allowed-groups}; sin
 * grupo permitido se usa {@code default-role} (vacio = sin rol). {@code scopes} son los scopes que se
 * conceden siempre, porque los tokens del login con usuario y contrasena no traen scopes propios.
 */
@ConfigurationProperties(prefix = "app.security.cognito")
public record CognitoProperties(
        boolean enabled,
        String issuerUri,
        String jwkSetUri,
        List<String> clientIds,
        List<String> allowedGroups,
        String defaultRole,
        List<String> scopes) {

    static final String PREFIX = "app.security.cognito.";

    public CognitoProperties {
        issuerUri = blankToNull(issuerUri);
        jwkSetUri = blankToNull(jwkSetUri);
        clientIds = clean(clientIds);
        allowedGroups = clean(allowedGroups);
        defaultRole = blankToNull(defaultRole);
        scopes = clean(scopes);
        if (enabled) {
            require(issuerUri != null, "issuer-uri es obligatorio cuando enabled=true (COGNITO_ISSUER_URI)");
            requireSafeUrl("issuer-uri", issuerUri);
            require(!issuerUri.endsWith("/"), "issuer-uri no debe terminar en '/': se compara de forma exacta con 'iss'");
            jwkSetUri = jwkSetUri == null ? issuerUri + "/.well-known/jwks.json" : jwkSetUri;
            requireSafeUrl("jwk-set-uri", jwkSetUri);
            require(!clientIds.isEmpty(), "client-ids es obligatorio cuando enabled=true (COGNITO_CLIENT_IDS)");
            require(!allowedGroups.isEmpty(), "allowed-groups no puede estar vacio");
            require(defaultRole == null || allowedGroups.contains(defaultRole),
                    "default-role debe estar en allowed-groups");
            require(!"ADMIN".equals(defaultRole), "default-role no puede ser ADMIN");
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
