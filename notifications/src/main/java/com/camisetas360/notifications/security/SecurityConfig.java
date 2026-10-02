package com.camisetas360.notifications.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

@Configuration
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
                        JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

                http
                                .csrf(csrf -> csrf.disable())

                                .cors(cors -> cors.disable())

                                .sessionManagement(session -> session.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))

                                .authorizeHttpRequests(auth -> auth

                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/actuator/health")
                                                .permitAll()

                                                .requestMatchers(
                                                                HttpMethod.OPTIONS,
                                                                "/**")
                                                .permitAll()

                                                .requestMatchers(
                                                                HttpMethod.POST,
                                                                "/api/v1/notifications/email")
                                                .access(
                                                                new WebExpressionAuthorizationManager(
                                                                                "hasRole('ADMIN') " +
                                                                                                "and hasAuthority(" +
                                                                                                "'SCOPE_Notifications.Send'"
                                                                                                +
                                                                                                ")"))

                                                .anyRequest()
                                                .denyAll())

                                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                                                jwtAuthenticationConverter)));

                return http.build();
        }
}