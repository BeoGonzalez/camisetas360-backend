package com.camisetas360.pago.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS solo en local ({@code app.cors.enabled=true}, perfil {@code local}). En produccion el CORS lo
 * resuelve el API Gateway: si este mapeo existiera siempre, Spring MVC rechazaria con
 * {@code 403 Invalid CORS request} toda peticion con un Origin distinto de localhost (el frontend publicado).
 */
@Configuration
@ConditionalOnProperty(name = "app.cors.enabled", havingValue = "true")
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origin-patterns:http://localhost:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
