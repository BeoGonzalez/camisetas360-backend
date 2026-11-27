package com.camisetas360.pago.security;

import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** Stable identity within the issuing Entra tenant; contact claims are intentionally excluded. */
public record EntraIdentity(UUID tenantId, UUID objectId) {
    public EntraIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(objectId, "objectId");
    }

    /**
     * Identity of an already validated token. Entra ID: {@code (tid, oid)}. Amazon Cognito (only the Cognito
     * decoder accepts {@code token_use}; Entra tokens never carry it): {@code tenantId} = UUID v3 of
     * {@code "cognito:" + iss} and {@code objectId} = {@code sub}, the same owner carrito stores.
     */
    public static EntraIdentity from(Jwt jwt) {
        if (jwt.hasClaim("token_use") && !jwt.hasClaim("tid")) {
            String issuer = jwt.getClaimAsString("iss");
            if (issuer == null || issuer.isBlank()) {
                throw new InvalidBearerTokenException("The access token must contain iss");
            }
            return new EntraIdentity(cognitoTenant(issuer), requiredUuid(jwt, "sub"));
        }
        return fromEntra(jwt);
    }

    static UUID cognitoTenant(String issuer) {
        return UUID.nameUUIDFromBytes(("cognito:" + issuer).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static EntraIdentity fromEntra(Jwt jwt) {
        return new EntraIdentity(requiredUuid(jwt, "tid"), requiredUuid(jwt, "oid"));
    }

    private static UUID requiredUuid(Jwt jwt, String name) {
        try {
            Object claim = jwt.getClaim(name);
            if (!(claim instanceof String value)) throw new IllegalArgumentException();
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidBearerTokenException("The access token must contain a canonical UUID " + name);
        }
    }
}
