package com.camisetas360.carrito.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class JwtSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Se deshabilita CSRF al operar puramente con tokens REST
                .authorizeHttpRequests(authz -> authz
                        // Exigimos un scope específico para poder registrar pedidos
                        .requestMatchers("/api/v1/carrito/**").hasAuthority("SCOPE_Cart.Write")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                );

        return http.build();
    }
}