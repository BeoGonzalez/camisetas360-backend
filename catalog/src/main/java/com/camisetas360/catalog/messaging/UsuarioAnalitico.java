package com.camisetas360.catalog.messaging;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identificador estable del usuario para analitica: tenant (tid) + objeto (oid) de Entra. Para Cognito
 * (token_use, sin tid) es UUID v3 de "cognito:" + iss + ":" + sub, el mismo usuarioId que publica carrito
 * y que usan recommendation y analytics. No usa email ni nombre, que pueden cambiar y son datos personales.
 */
public final class UsuarioAnalitico {

    private UsuarioAnalitico() {
    }

    public static String desde(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tid");
        String object = jwt.getClaimAsString("oid");
        if (tenant != null && !tenant.isBlank() && object != null && !object.isBlank()) {
            return tenant.toLowerCase() + ":" + object.toLowerCase();
        }
        String issuer = jwt.getClaimAsString("iss");
        if (jwt.hasClaim("token_use") && issuer != null && jwt.getSubject() != null) {
            var tenantCognito = java.util.UUID.nameUUIDFromBytes(
                    ("cognito:" + issuer).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return tenantCognito + ":" + jwt.getSubject().toLowerCase();
        }
        return "sub:" + jwt.getSubject();
    }
}
