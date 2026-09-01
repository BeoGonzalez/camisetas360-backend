package com.camisetas360.catalog.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuración de seguridad que actúa como Backend For Frontend (BFF).
 * Valida el token JWT interceptando cada solicitud entrante para verificar accesos.
 */
@Configuration
public class JwtSecurityConfig {

    /**
     * Configura la cadena de filtros de seguridad para validar el JWT
     * utilizando la información del IDaaS perimetral.
     *
     * @param http Objeto HttpSecurity para definir reglas web.
     * @return SecurityFilterChain configurado.
     * @throws Exception en caso de error de configuración.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/v1/catalog/**").authenticated()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {}) // La lógica de validación extrae las llaves del application.yml
            );

        return http.build();
    }
}