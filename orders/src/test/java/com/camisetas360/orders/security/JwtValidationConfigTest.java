package com.camisetas360.orders.security;

import com.camisetas360.testing.security.DecoderStartupContract;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtValidationConfigTest implements DecoderStartupContract {
    public JwtDecoder decoder(OAuth2ResourceServerProperties properties) {
        return new JwtValidationConfig().jwtDecoder(properties);
    }
}
