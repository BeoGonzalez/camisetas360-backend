package com.camisetas360.notifications.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.disable())

                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth

                        // Health check para Docker/monitorización
                        .requestMatchers(
                                HttpMethod.GET,
                                "/actuator/health")
                        .permitAll()

                        // Preflight
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**")
                        .permitAll()

                        // Envío manual/directo de correos
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/notifications/email")
                        .hasAuthority("SCOPE_Notifications.Send")

                        // Todo lo demás queda bloqueado
                        .anyRequest()
                        .denyAll())

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }));

        return http.build();
    }
}