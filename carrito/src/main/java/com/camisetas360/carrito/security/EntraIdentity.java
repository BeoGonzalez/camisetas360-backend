package com.camisetas360.carrito.security;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Stable identity of the cart owner; contact claims are intentionally excluded.
 * <ul>
 *   <li>Entra ID: {@code (tid, oid)}.</li>
 *   <li>Amazon Cognito: {@code tenantId} = UUID v3 derived from the user pool issuer (never collides with an
 *       Entra tenant id, which is random) and {@code objectId} = {@code sub}, already a UUID. The cart, the
 *       checkout event and the analytics {@code usuarioId} keep the same shape without schema changes.</li>
 * </ul>
 */
public record EntraIdentity(UUID tenantId, UUID objectId) {
    public EntraIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(objectId, "objectId");
    }

    /**
     * Identity of an already validated token. Only the Cognito decoder accepts {@code token_use}; Entra
     * tokens never carry it, so they always go through {@link #fromEntra(Jwt)}.
     */
    public static EntraIdentity from(Jwt jwt) {
        if (jwt.hasClaim("token_use") && !jwt.hasClaim("tid")) {
            return fromCognito(jwt);
        }
        return fromEntra(jwt);
    }

    public static EntraIdentity fromEntra(Jwt jwt) {
        return new EntraIdentity(requiredUuid(jwt, "tid"), requiredUuid(jwt, "oid"));
    }

    static EntraIdentity fromCognito(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || issuer.isBlank()) {
            throw new InvalidBearerTokenException("The access token must contain iss");
        }
        return new EntraIdentity(cognitoTenant(issuer), requiredUuid(jwt, "sub"));
    }

    static UUID cognitoTenant(String issuer) {
        return UUID.nameUUIDFromBytes(("cognito:" + issuer).getBytes(StandardCharsets.UTF_8));
    }

    static boolean esUuidCanonico(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static UUID requiredUuid(Jwt jwt, String name) {
        Object claim = jwt.getClaim(name);
        if (!(claim instanceof String value) || !esUuidCanonico(value)) {
            throw new InvalidBearerTokenException("The access token must contain a canonical UUID " + name);
        }
        return UUID.fromString(value);
    }
}
