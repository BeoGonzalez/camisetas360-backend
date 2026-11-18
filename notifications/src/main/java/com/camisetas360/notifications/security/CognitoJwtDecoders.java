package com.camisetas360.notifications.security;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Decoder de los access tokens de Cognito (que no traen {@code aud}). No es un bean: el unico
 * {@link JwtDecoder} del contexto sigue siendo el de Entra.
 */
final class CognitoJwtDecoders {
    private CognitoJwtDecoders() { }

    /** Perezoso: el JWKS de Cognito no se descarga al arrancar, igual que el de Entra. */
    static JwtDecoder create(CognitoProperties properties) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators(properties)));
            return decoder;
        });
    }

    static List<OAuth2TokenValidator<Jwt>> validators(CognitoProperties properties) {
        var timestamp = new JwtTimestampValidator();
        timestamp.setAllowEmptyExpiryClaim(false);
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(JwtValidators.createDefaultWithIssuer(properties.issuerUri()));
        validators.add(timestamp);
        validators.add(claim("token_use", "access"::equals, "Solo se aceptan access tokens de Cognito"));
        validators.add(claim("client_id", properties.clientIds()::contains, "client_id no autorizado"));
        // El sub de Cognito es un UUID canonico: identifica al usuario igual en todos los servicios.
        validators.add(claim("sub", CognitoJwtDecoders::esUuidCanonico, "El sub debe ser un UUID"));
        // Los tokens M2M (client_credentials) no llevan username: estas APIs son para personas.
        validators.add(claim("username", value -> !value.isBlank(), "Se requiere un token de usuario"));
        return validators;
    }

    static boolean esUuidCanonico(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** El claim debe existir, ser texto y cumplir la condicion; cualquier otro tipo se rechaza. */
    private static OAuth2TokenValidator<Jwt> claim(String name, Predicate<String> condition, String description) {
        var error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null);
        return jwt -> jwt.getClaims().get(name) instanceof String value && condition.test(value)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
