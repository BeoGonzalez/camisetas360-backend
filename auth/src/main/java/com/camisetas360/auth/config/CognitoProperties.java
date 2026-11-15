package com.camisetas360.auth.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Confianza en access tokens de Amazon Cognito. Apagado por defecto; con {@code enabled=true} la
 * configuracion incompleta o insegura hace fallar el arranque (fail-fast en el constructor compacto,
 * sin depender de una implementacion de Bean Validation).
 */
@ConfigurationProperties(prefix = "app.security.cognito")
public record CognitoProperties(
        boolean enabled,
        String issuerUri,
        String jwkSetUri,
        List<String> clientIds,
        String resourceServerId,
        List<String> allowedGroups,
        String defaultRole,
        String userInfoUri,
        Duration userInfoTimeout) {

    static final String PREFIX = "app.security.cognito.";
    static final String PRIVILEGED_ROLE = "ADMIN";
    static final Duration DEFAULT_USER_INFO_TIMEOUT = Duration.ofSeconds(2);
    static final Duration MAX_USER_INFO_TIMEOUT = Duration.ofSeconds(10);

    public CognitoProperties {
        issuerUri = blankToNull(issuerUri);
        jwkSetUri = blankToNull(jwkSetUri);
        resourceServerId = blankToNull(resourceServerId);
        userInfoUri = blankToNull(userInfoUri);
        clientIds = clean(clientIds);
        allowedGroups = allowedGroups == null ? List.of("CUSTOMER") : clean(allowedGroups);
        defaultRole = defaultRole == null || defaultRole.isBlank() ? "CUSTOMER" : defaultRole.trim();
        userInfoTimeout = userInfoTimeout == null ? DEFAULT_USER_INFO_TIMEOUT : userInfoTimeout;
        if (enabled) {
            require(issuerUri != null, "issuer-uri es obligatorio cuando enabled=true (COGNITO_ISSUER_URI)");
            requireSafeUrl("issuer-uri", issuerUri);
            require(!issuerUri.endsWith("/"), "issuer-uri no debe terminar en '/': se compara de forma exacta con 'iss'");
            String path = URI.create(issuerUri).getPath();
            require(path != null && path.length() > 1, "issuer-uri debe terminar en el id del user pool");
            jwkSetUri = jwkSetUri == null ? issuerUri + "/.well-known/jwks.json" : jwkSetUri;
            requireSafeUrl("jwk-set-uri", jwkSetUri);
            require(!clientIds.isEmpty(), "client-ids es obligatorio cuando enabled=true (COGNITO_CLIENT_IDS)");
            require(resourceServerId != null,
                    "resource-server-id es obligatorio cuando enabled=true (COGNITO_RESOURCE_SERVER_ID)");
            require(!resourceServerId.endsWith("/"), "resource-server-id no debe terminar en '/'");
            require(!allowedGroups.isEmpty(), "allowed-groups no puede estar vacio (COGNITO_ALLOWED_GROUPS)");
            require(allowedGroups.contains(defaultRole), "default-role debe pertenecer a allowed-groups");
            require(!PRIVILEGED_ROLE.equals(defaultRole), "default-role no puede ser ADMIN");
            if (userInfoUri != null) requireSafeUrl("user-info-uri", userInfoUri);
            require(userInfoTimeout.isPositive() && userInfoTimeout.compareTo(MAX_USER_INFO_TIMEOUT) <= 0,
                    "user-info-timeout debe estar entre 1ms y 10s");
        }
    }

    /** Ultimo segmento del issuer de Cognito: {@code https://cognito-idp.<region>.amazonaws.com/<userPoolId>}. */
    public String userPoolId() {
        return issuerUri == null ? null : userPoolId(issuerUri);
    }

    /** Prefijo que Cognito antepone a los scopes personalizados del resource server. */
    public String scopePrefix() {
        return resourceServerId + "/";
    }

    private static String userPoolId(String issuer) {
        return issuer.substring(issuer.lastIndexOf('/') + 1);
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

    /** HTTPS obligatorio; HTTP solo en loopback (tests con JWKS local). Evita enviar tokens en claro. */
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
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host));
        require(https || loopback, property + " debe usar https (http solo se admite en loopback)");
        require(uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                property + " no debe incluir credenciales, query ni fragmento");
    }
}
