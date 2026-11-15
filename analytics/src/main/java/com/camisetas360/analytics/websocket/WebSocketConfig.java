package com.camisetas360.analytics.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP sobre WebSocket para el dashboard de metricas.
 * - /ws/metricas:        WebSocket nativo (ws:// o wss://).
 * - /ws/metricas-sockjs: mismo endpoint con fallback SockJS para redes que bloquean WebSocket.
 * Los clientes se suscriben a /topic/metricas.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompJwtChannelInterceptor interceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompJwtChannelInterceptor interceptor,
                           @Value("${app.websocket.allowed-origin-patterns:http://localhost:*}") String[] allowedOrigins) {
        this.interceptor = interceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/metricas").setAllowedOriginPatterns(allowedOrigins);
        registry.addEndpoint("/ws/metricas-sockjs").setAllowedOriginPatterns(allowedOrigins).withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor);
    }
}
