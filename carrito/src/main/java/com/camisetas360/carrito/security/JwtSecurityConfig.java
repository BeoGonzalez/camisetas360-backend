package com.camisetas360.carrito.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class JwtSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                // CORS lo administra AWS API Gateway
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(authz -> authz

                        // Dejamos OPTIONS libre por seguridad.
                        // Nunca debe exigir JWT.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()

                        // Las operaciones reales del carrito requieren Cart.Write
                        .requestMatchers("/api/v1/carrito/**")
                        .hasAuthority("SCOPE_Cart.Write")

                        .anyRequest()
                        .authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {
                        })
                );

        return http.build();
    }
}