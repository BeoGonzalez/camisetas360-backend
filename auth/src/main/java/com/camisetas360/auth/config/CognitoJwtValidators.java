package com.camisetas360.auth.config;

import java.util.List;
import java.util.function.Predicate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validadores propios de los access tokens de Cognito (que no traen {@code aud}). Issuer, firma y
 * timestamps los aporta {@link JwtValidationConfig#lazyDecoder}.
 */
final class CognitoJwtValidators {
    private CognitoJwtValidators() { }

    static List<OAuth2TokenValidator<Jwt>> create(CognitoProperties properties) {
        return List.of(
                claim("token_use", "access"::equals, "Solo se aceptan access tokens de Cognito"),
                claim("client_id", properties.clientIds()::contains, "client_id no autorizado"),
                claim("sub", value -> !value.isBlank(), "El token debe identificar al sujeto"),
                // Los tokens M2M (client_credentials) no llevan username: /profile es solo para personas.
                claim("username", value -> !value.isBlank(), "Se requiere un token de usuario, no de cliente"));
    }

    /** El claim debe existir, ser String y cumplir la condicion; cualquier otro tipo se rechaza. */
    private static OAuth2TokenValidator<Jwt> claim(String name, Predicate<String> condition, String description) {
        var error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null);
        return jwt -> jwt.getClaims().get(name) instanceof String value && condition.test(value)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
