package com.camisetas360.analytics.security;

import com.camisetas360.analytics.websocket.StompJwtChannelInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Usa tokens firmados reales (JWKS local), sin decoder simulado. */
class StompJwtChannelInterceptorTest {

    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();
    private static final MessageChannel CHANNEL = mock(MessageChannel.class);

    private final StompJwtChannelInterceptor interceptor = new StompJwtChannelInterceptor(decoder());

    @AfterAll
    static void stop() {
        ISSUER.close();
    }

    private static JwtDecoder decoder() {
        var properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri(ISSUER.issuer());
        properties.getJwt().setJwkSetUri(ISSUER.jwksUri());
        properties.getJwt().setAudiences(List.of(LocalJwtIssuer.AUDIENCE));
        return new JwtValidationConfig().jwtDecoder(properties);
    }

    private static Message<byte[]> frame(StompCommand command, String authorization, String destination,
                                         JwtAuthenticationToken user) {
        var accessor = StompHeaderAccessor.create(command);
        if (authorization != null) accessor.addNativeHeader("Authorization", authorization);
        if (destination != null) accessor.setDestination(destination);
        if (user != null) accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER"})
    void connect_autenticaAdministradoresConScope(String rol) throws Exception {
        var message = frame(StompCommand.CONNECT, "Bearer " + ISSUER.token("Catalog.Read", "role-" + rol), null, null);

        interceptor.preSend(message, CHANNEL);

        var user = StompHeaderAccessor.wrap(message).getUser();
        assertThat(user).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) user).getAuthorities())
                .extracting(Object::toString).contains("ROLE_" + rol, "SCOPE_Catalog.Read");
    }

    @Test
    void connect_rechazaClientesSinRolDeDashboard() throws Exception {
        var message = frame(StompCommand.CONNECT, "Bearer " + ISSUER.token("Catalog.Read", "valid"), null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, CHANNEL)).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connect_rechazaSinScope() throws Exception {
        var message = frame(StompCommand.CONNECT, "Bearer " + ISSUER.token("", "role-ADMIN"), null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, CHANNEL)).isInstanceOf(MessageDeliveryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "signature", "audience", "issuer", "malformed", "missing-object"})
    void connect_rechazaTokensInvalidos(String variante) throws Exception {
        var message = frame(StompCommand.CONNECT, "Bearer " + ISSUER.token("Catalog.Read", variante.equals("malformed")
                ? "malformed" : variante), null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, CHANNEL)).isInstanceOf(MessageDeliveryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic abc", "Bearer "})
    void connect_rechazaSinBearer(String header) {
        var message = frame(StompCommand.CONNECT, header.isEmpty() ? null : header, null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, CHANNEL)).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void subscribe_requiereSesionAutenticadaYDestinoDeMetricas() throws Exception {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null, "/topic/metricas", null), CHANNEL))
                .isInstanceOf(MessageDeliveryException.class);

        var connect = frame(StompCommand.CONNECT, "Bearer " + ISSUER.token("Catalog.Read", "role-ADMIN"), null, null);
        interceptor.preSend(connect, CHANNEL);
        var user = (JwtAuthenticationToken) StompHeaderAccessor.wrap(connect).getUser();

        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, null, "/topic/metricas", user), CHANNEL)).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null, "/topic/otro", user), CHANNEL))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void send_estaProhibido() throws Exception {
        var connect = frame(StompCommand.CONNECT, "Bearer " + ISSUER.token("Catalog.Read", "role-ADMIN"), null, null);
        interceptor.preSend(connect, CHANNEL);
        var user = (JwtAuthenticationToken) StompHeaderAccessor.wrap(connect).getUser();

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null, "/app/x", user), CHANNEL))
                .isInstanceOf(MessageDeliveryException.class);
    }
}
