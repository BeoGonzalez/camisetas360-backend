package com.camisetas360.catalog.security;

import com.camisetas360.catalog.config.CorsProperties;

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
        public SecurityFilterChain filterChain(
                        HttpSecurity http,
                        CorsConfigurationSource corsConfigurationSource,
                        JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

                http
                                .csrf(csrf -> csrf.disable())

                                .cors(cors -> cors.configurationSource(
                                                corsConfigurationSource))

                                .sessionManagement(session -> session.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))

                                .authorizeHttpRequests(auth -> auth

                                                .requestMatchers(
                                                                HttpMethod.OPTIONS,
                                                                "/**")
                                                .permitAll()

                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/api/v1/catalog/**")
                                                .access(
                                                                new WebExpressionAuthorizationManager(
                                                                                "hasAnyRole(" +
                                                                                                "'CUSTOMER'," +
                                                                                                "'CATALOG_MANAGER'," +
                                                                                                "'ADMIN'" +
                                                                                                ") and " +
                                                                                                "hasAuthority('SCOPE_Catalog.Read')"))

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
                                                "Content-Type",
                                                "Accept"));

                configuration.setAllowCredentials(false);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                source.registerCorsConfiguration(
                                "/**",
                                configuration);

                return source;
        }
}