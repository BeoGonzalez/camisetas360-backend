package com.camisetas360.carrito.security;

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

    public static EntraIdentity from(Jwt jwt) {
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
