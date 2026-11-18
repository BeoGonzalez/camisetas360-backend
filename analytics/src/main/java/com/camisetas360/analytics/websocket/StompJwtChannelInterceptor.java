package com.camisetas360.analytics.websocket;

import com.camisetas360.analytics.security.StompTokenAuthenticator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * Autenticacion del dashboard por WebSocket.
 *
 * Un navegador no puede enviar el header Authorization en el handshake HTTP del WebSocket,
 * por eso el handshake es publico y el token (Entra ID o Cognito) se exige en el frame STOMP CONNECT
 * (header "Authorization: Bearer ..."). Solo ADMIN o CATALOG_MANAGER con scope Catalog.Read
 * pueden conectarse, y solo pueden suscribirse a /topic/metricas.
 */
@Component
public class StompJwtChannelInterceptor implements ChannelInterceptor {

    public static final String DESTINO_METRICAS = "/topic/metricas";
    private static final Set<String> ROLES = Set.of("ROLE_ADMIN", "ROLE_CATALOG_MANAGER");
    private static final String SCOPE = "SCOPE_Catalog.Read";

    private final StompTokenAuthenticator authenticator;

    @Autowired
    public StompJwtChannelInterceptor(StompTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /** Solo Entra ID. */
    public StompJwtChannelInterceptor(JwtDecoder decoder, String issuer) {
        this(StompTokenAuthenticator.soloEntra(issuer, decoder));
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            accessor.setUser(autenticar(accessor.getFirstNativeHeader("Authorization")));
        } else if (command == StompCommand.SUBSCRIBE) {
            if (accessor.getUser() == null) {
                throw new MessageDeliveryException("Conexion no autenticada");
            }
            if (!DESTINO_METRICAS.equals(accessor.getDestination())) {
                throw new MessageDeliveryException("Destino no permitido");
            }
        } else if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("El dashboard es de solo lectura");
        }
        return message;
    }

    private JwtAuthenticationToken autenticar(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            throw new MessageDeliveryException("Falta el token Bearer en STOMP CONNECT");
        }
        JwtAuthenticationToken autenticado;
        try {
            autenticado = authenticator.autenticar(header.substring(7).trim());
        } catch (AuthenticationException invalido) {
            throw new MessageDeliveryException("Token invalido");
        }
        Collection<GrantedAuthority> authorities = autenticado.getAuthorities();
        boolean rol = authorities.stream().anyMatch(a -> ROLES.contains(a.getAuthority()));
        boolean scope = authorities.stream().anyMatch(a -> SCOPE.equals(a.getAuthority()));
        if (!rol || !scope) {
            throw new MessageDeliveryException("Permisos insuficientes para el dashboard");
        }
        return autenticado;
    }
}
