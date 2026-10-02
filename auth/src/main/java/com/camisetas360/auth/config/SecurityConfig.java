package com.camisetas360.auth.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

        @Bean
        public JwtAuthenticationConverter jwtAuthenticationConverter() {

                JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

                converter.setJwtGrantedAuthoritiesConverter(
                                new JwtAuthoritiesConverter());

                return converter;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        CorsConfigurationSource corsConfigurationSource,
                        JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

                http
                                .csrf(csrf -> csrf.disable())

                                .cors(cors -> cors.configurationSource(
                                                corsConfigurationSource))

                                .sessionManagement(session -> session.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))

                                .authorizeHttpRequests(authorize -> authorize

                                                .requestMatchers(
                                                                HttpMethod.OPTIONS,
                                                                "/**")
                                                .permitAll()

                                                .requestMatchers(
                                                                "/actuator/health")
                                                .permitAll()

                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/api/v1/auth/profile")
                                                .access(
                                                                new WebExpressionAuthorizationManager(
                                                                                "hasAnyRole(" +
                                                                                                "'CUSTOMER'," +
                                                                                                "'CATALOG_MANAGER'," +
                                                                                                "'ADMIN'" +
                                                                                                ") and " +
                                                                                                "hasAuthority('SCOPE_Profile.Read')"))

                                                .anyRequest()
                                                .denyAll())

                                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                                                jwtAuthenticationConverter)));

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource(
                        CorsProperties corsProperties) {

                CorsConfiguration configuration = new CorsConfiguration();

                configuration.setAllowedOriginPatterns(
                                corsProperties.allowedOriginPatterns());

                configuration.setAllowedMethods(
                                List.of(
                                                "GET",
                                                "OPTIONS"));

                configuration.setAllowedHeaders(
                                List.of(
                                                "Authorization",
                                                "Content-Type"));

                configuration.setAllowCredentials(true);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                source.registerCorsConfiguration(
                                "/**",
                                configuration);

                return source;
        }
}