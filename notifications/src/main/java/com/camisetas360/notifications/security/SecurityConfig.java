package com.camisetas360.notifications.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Import(JwtValidationConfig.class)
@EnableConfigurationProperties(CognitoProperties.class)
public class SecurityConfig {
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtAuthoritiesConverter());
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            @Value("${app.cors.enabled:false}") boolean localCors, JwtDecoder entraDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String entraIssuer,
            CognitoProperties cognito) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(config -> {
                    if (localCors) config.configurationSource(cors);
                    else config.disable(); // Production CORS belongs to API Gateway.
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // sendError (400/404/405/415...) reenvia a /error: debe conservar el estado original, no volverse 401/403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications/email")
                        .access(new WebExpressionAuthorizationManager(
                                "hasRole('ADMIN') and hasAuthority('SCOPE_Notifications.Send')"))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> {
                    if (cognito.enabled()) {
                        resource.authenticationManagerResolver(issuerResolver(entraIssuer, entraDecoder, converter, cognito));
                    } else {
                        resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter));
                    }
                });
        return http.build();
    }

    /**
     * Entra ID (siempre) + Cognito: cada token se enruta por su {@code iss} exacto a su propio decoder; un
     * issuer desconocido no obtiene manager y el token se rechaza con 401.
     */
    static JwtIssuerAuthenticationManagerResolver issuerResolver(String entraIssuer, JwtDecoder entraDecoder,
            JwtAuthenticationConverter entraConverter, CognitoProperties cognito) {
        var managers = managers(entraIssuer, entraDecoder, entraConverter, cognito);
        return new JwtIssuerAuthenticationManagerResolver(issuer -> issuer == null ? null : managers.get(issuer));
    }

    /** Un AuthenticationManager por issuer de confianza (Entra siempre; Cognito si esta habilitado). */
    static Map<String, AuthenticationManager> managers(String entraIssuer, JwtDecoder entraDecoder,
            JwtAuthenticationConverter entraConverter, CognitoProperties cognito) {
        if (!cognito.enabled()) {
            return Map.of(entraIssuer, manager(entraDecoder, entraConverter));
        }
        if (cognito.issuerUri().equals(entraIssuer)) {
            throw new IllegalStateException(CognitoProperties.PREFIX + "issuer-uri no puede coincidir con el issuer de Entra ID");
        }
        var cognitoConverter = new JwtAuthenticationConverter();
        cognitoConverter.setJwtGrantedAuthoritiesConverter(new CognitoAuthoritiesConverter(cognito));
        return Map.of(
                entraIssuer, manager(entraDecoder, entraConverter),
                cognito.issuerUri(), manager(CognitoJwtDecoders.create(cognito), cognitoConverter));
    }

    private static AuthenticationManager manager(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return new ProviderManager(provider);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(Environment environment) {
        var origins = Binder.get(environment)
                .bind("app.cors.allowed-origin-patterns", Bindable.listOf(String.class)).orElse(List.of());
        boolean local = environment.getProperty("app.cors.enabled", Boolean.class, false);
        if (local && (origins.isEmpty() || origins.stream().anyMatch(String::isBlank))) {
            throw new IllegalStateException("Local CORS requires an explicit allowed origin");
        }
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        var result = new UrlBasedCorsConfigurationSource();
        result.registerCorsConfiguration("/**", config);
        return result;
    }
}
