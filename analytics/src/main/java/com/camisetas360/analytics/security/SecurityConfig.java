package com.camisetas360.analytics.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Import(JwtValidationConfig.class)
public class SecurityConfig {

    static final String CLIENTE = "hasRole('CUSTOMER') and hasAuthority('SCOPE_Catalog.Read')";
    static final String DASHBOARD = "hasAnyRole('ADMIN','CATALOG_MANAGER') and hasAuthority('SCOPE_Catalog.Read')";

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtAuthoritiesConverter());
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            @Value("${app.cors.enabled:false}") boolean localCors) throws Exception {
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
                        // Handshake WebSocket/SockJS publico: el JWT se valida en STOMP CONNECT
                        // (StompJwtChannelInterceptor) porque el navegador no envia Authorization aqui.
                        .requestMatchers(HttpMethod.GET, "/ws/metricas", "/ws/metricas-sockjs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/ws/metricas-sockjs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/recomendaciones")
                        .access(new WebExpressionAuthorizationManager(CLIENTE))
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/metricas")
                        .access(new WebExpressionAuthorizationManager(DASHBOARD))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
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
        config.setAllowedMethods(List.of("GET", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        var result = new UrlBasedCorsConfigurationSource();
        result.registerCorsConfiguration("/api/**", config);
        return result;
    }
}
