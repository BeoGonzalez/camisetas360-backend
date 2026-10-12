package com.camisetas360.notifications.security;

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
    @Bean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        var settings = properties.getJwt();
        String issuer = settings.getIssuerUri();
        var audiences = settings.getAudiences();
        if (issuer == null || issuer.isBlank() || audiences == null || audiences.isEmpty()) {
            throw new IllegalStateException("Issuer and API audience must be configured");
        }

        // Discovery/JWKS availability must not prevent startup or unauthenticated health checks.
        return new SupplierJwtDecoder(() -> {
            // An explicit JWKS URL supports local signed-token tests without weakening issuer validation.
            NimbusJwtDecoder decoder = settings.getJwkSetUri() != null && !settings.getJwkSetUri().isBlank()
                    ? NimbusJwtDecoder.withJwkSetUri(settings.getJwkSetUri()).build()
                    : (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
            var timestamp = new JwtTimestampValidator();
            timestamp.setAllowEmptyExpiryClaim(false); // Normal Entra access tokens contain exp.
            var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
            validators.add(timestamp);
            validators.add(new JwtClaimValidator<List<String>>("aud",
                    aud -> aud != null && aud.stream().anyMatch(audiences::contains)));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }
}
