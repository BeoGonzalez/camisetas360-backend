package com.camisetas360.catalog.messaging;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identificador estable del usuario para analitica: tenant (tid) + objeto (oid) de Entra.
 * No usa email ni nombre, que pueden cambiar y son datos personales.
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
        return "sub:" + jwt.getSubject();
    }
}
