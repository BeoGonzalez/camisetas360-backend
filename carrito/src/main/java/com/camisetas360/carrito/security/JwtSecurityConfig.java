package com.camisetas360.carrito.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class JwtSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                // API REST con JWT: no usamos CSRF
                .csrf(csrf -> csrf.disable())

                // CORS lo administra AWS API Gateway
                .cors(cors -> cors.disable())

                // API completamente stateless
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        // El preflight OPTIONS nunca debe exigir JWT
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()

                        // Checkout y operaciones del carrito protegidas
                        .requestMatchers("/api/v1/carrito/**")
                        .hasAuthority("SCOPE_Cart.Write")

                        // Cualquier otra ruta requiere autenticación
                        .anyRequest()
                        .authenticated()
                )

                // Microsoft Entra ID / OAuth2 JWT
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> {
                        })
                );

        return http.build();
    }
}