package com.camisetas360.auth.identity;

import com.camisetas360.auth.dtos.UserProfileDTO;
import org.springframework.security.oauth2.jwt.Jwt;

/** Normaliza los claims de un proveedor de identidad al perfil comun (userId, tenantId, email, name). */
public interface ClaimsMapper {

    UserProfileDTO map(Jwt jwt);

    /**
     * {@code true} si un email/name ausente en el token no debe borrar el valor ya persistido
     * (p. ej. Cognito sin la Lambda Pre Token Generation y con el fallback userInfo caido).
     */
    default boolean keepsStoredContactWhenMissing() {
        return false;
    }
}
