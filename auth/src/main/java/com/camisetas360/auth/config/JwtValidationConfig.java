package com.camisetas360.auth.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class JwtValidationConfig {

    /** Decoder de Microsoft Entra ID: unico bean {@link JwtDecoder} del contexto. */
    @Bean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        var settings = properties.getJwt();
        String issuer = settings.getIssuerUri();
        var audiences = settings.getAudiences();
        if (issuer == null || issuer.isBlank() || audiences == null || audiences.isEmpty()) {
            throw new IllegalStateException("Issuer and API audience must be configured");
        }
        return lazyDecoder(issuer, settings.getJwkSetUri(), List.of(new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.stream().anyMatch(audiences::contains))));
    }

    /**
     * Decoder perezoso compartido por todos los issuers de confianza: valida firma con el JWKS del issuer,
     * {@code iss} exacto, {@code exp} obligatorio, {@code nbf} y los validadores propios del proveedor.
     * Discovery/JWKS no disponibles no impiden el arranque ni los health checks no autenticados.
     */
    static JwtDecoder lazyDecoder(String issuer, String jwkSetUri, List<OAuth2TokenValidator<Jwt>> providerValidators) {
        return new SupplierJwtDecoder(() -> {
            // An explicit JWKS URL supports local signed-token tests without weakening issuer validation.
            NimbusJwtDecoder decoder = jwkSetUri != null && !jwkSetUri.isBlank()
                    ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                    : (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
            var timestamp = new JwtTimestampValidator();
            timestamp.setAllowEmptyExpiryClaim(false); // Entra and Cognito access tokens always contain exp.
            var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
            validators.add(timestamp);
            validators.addAll(providerValidators);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }
}
