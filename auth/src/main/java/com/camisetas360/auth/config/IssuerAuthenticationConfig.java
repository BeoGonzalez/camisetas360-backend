package com.camisetas360.auth.config;

import com.camisetas360.auth.identity.ClaimsMapper;
import com.camisetas360.auth.identity.CognitoClaimsMapper;
import com.camisetas360.auth.identity.CognitoUserInfoClient;
import com.camisetas360.auth.identity.EntraClaimsMapper;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

/**
 * Multi-issuer: Entra ID (siempre) + Cognito (opcional). El decoder de Cognito NO se registra como bean
 * {@link JwtDecoder}: el unico bean de ese tipo sigue siendo el de Entra.
 */
@Configuration(proxyBeanMethods = false)
@Import(JwtValidationConfig.class)
@EnableConfigurationProperties({OAuth2ResourceServerProperties.class, CognitoProperties.class})
public class IssuerAuthenticationConfig {
    private static final Logger log = LoggerFactory.getLogger(IssuerAuthenticationConfig.class);

    @Bean
    public TrustedIssuers trustedIssuers(JwtDecoder entraDecoder, JwtAuthenticationConverter entraConverter,
            OAuth2ResourceServerProperties resourceServer, CognitoProperties cognito) {
        String entraIssuer = resourceServer.getJwt().getIssuerUri();
        ClaimsMapper entraClaims = new EntraClaimsMapper();
        var trusts = new LinkedHashMap<String, TrustedIssuers.Trust>();
        trusts.put(entraIssuer, new TrustedIssuers.Trust(manager(entraDecoder, entraConverter), entraClaims));

        if (cognito.enabled()) {
            if (cognito.issuerUri().equals(entraIssuer)) {
                throw new IllegalStateException(CognitoProperties.PREFIX + "issuer-uri no puede coincidir con el issuer de Entra ID");
            }
            JwtDecoder decoder = JwtValidationConfig.lazyDecoder(cognito.issuerUri(), cognito.jwkSetUri(),
                    CognitoJwtValidators.create(cognito));
            var converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(new CognitoAuthoritiesConverter(cognito));
            var userInfo = cognito.userInfoUri() == null ? null
                    : new CognitoUserInfoClient(cognito.userInfoUri(), cognito.userInfoTimeout());
            trusts.put(cognito.issuerUri(), new TrustedIssuers.Trust(manager(decoder, converter),
                    new CognitoClaimsMapper(cognito.userPoolId(), userInfo)));
        }
        log.info("Issuers JWT de confianza: {}", trusts.keySet());
        return new TrustedIssuers(trusts, entraClaims);
    }

    @Bean
    public JwtIssuerAuthenticationManagerResolver issuerAuthenticationManagerResolver(TrustedIssuers trustedIssuers) {
        return new JwtIssuerAuthenticationManagerResolver(trustedIssuers::authenticationManager);
    }

    private static AuthenticationManager manager(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return new ProviderManager(provider);
    }
}
