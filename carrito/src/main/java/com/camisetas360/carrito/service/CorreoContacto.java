package com.camisetas360.carrito.service;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Email de contacto que el token no trae (los access tokens de Cognito no incluyen {@code email}),
 * consultado al proveedor de identidad que lo emitio. Vacio si no aplica o si la consulta falla.
 */
@FunctionalInterface
public interface CorreoContacto {

    CorreoContacto NINGUNO = jwt -> Optional.empty();

    Optional<String> buscar(Jwt jwt);
}
