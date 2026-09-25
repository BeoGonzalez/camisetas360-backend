package com.camisetas360.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOriginPatterns) {
}