package com.camisetas360.testing.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.assertj.core.api.Assertions.*;

public interface DecoderStartupContract {
    JwtDecoder decoder(OAuth2ResourceServerProperties properties);

    @Test default void discoveryUnavailableDoesNotPreventStartup() {
        var properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri("http://127.0.0.1:1/offline");
        properties.getJwt().setAudiences(List.of("api"));
        assertThatCode(() -> decoder(properties)).doesNotThrowAnyException();
    }

    @Test default void missingIssuerFailsClosed() {
        var properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setAudiences(List.of("api"));
        assertThatThrownBy(() -> decoder(properties)).isInstanceOf(IllegalStateException.class);
    }

    @Test default void missingAudienceFailsClosed() {
        var properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri("http://127.0.0.1:1/offline");
        assertThatThrownBy(() -> decoder(properties)).isInstanceOf(IllegalStateException.class);
    }
}
