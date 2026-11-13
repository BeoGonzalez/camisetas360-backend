package com.camisetas360.telemetria;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.camisetas360.telemetria.auth.EntraTokenValidator;
import com.camisetas360.telemetria.auth.TokenInvalidoException;
import com.camisetas360.telemetria.conexiones.ConexionesRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MensajeroHandlerTest {

    static final Instant AHORA = Instant.parse("2026-11-12T10:00:00Z");

    final ConexionesRepository conexiones = mock(ConexionesRepository.class);
    final EntraTokenValidator validator = mock(EntraTokenValidator.class);
    final MensajeroHandler handler = new MensajeroHandler(conexiones, validator, Clock.fixed(AHORA, ZoneOffset.UTC));

    static APIGatewayV2WebSocketEvent evento(String ruta, Map<String, String> query) {
        var ctx = new APIGatewayV2WebSocketEvent.RequestContext();
        ctx.setRouteKey(ruta);
        ctx.setConnectionId("conn-1");
        var event = new APIGatewayV2WebSocketEvent();
        event.setRequestContext(ctx);
        event.setQueryStringParameters(query);
        return event;
    }

    @Test
    void connectConTokenValido_guardaLaConexionConElUsuario() throws Exception {
        when(validator.validar("jwt")).thenReturn("tid:oid");

        var respuesta = handler.handleRequest(evento("$connect", Map.of("token", "jwt")), null);

        assertThat(respuesta.getStatusCode()).isEqualTo(200);
        verify(conexiones).guardar("conn-1", "tid:oid", AHORA);
    }

    @Test
    void connectConTokenInvalidoOSinToken_responde401YNoGuarda() throws Exception {
        when(validator.validar(any())).thenThrow(new TokenInvalidoException("Falta el token"));

        assertThat(handler.handleRequest(evento("$connect", Map.of("token", "malo")), null).getStatusCode()).isEqualTo(401);
        assertThat(handler.handleRequest(evento("$connect", null), null).getStatusCode()).isEqualTo(401);
        verify(conexiones, never()).guardar(anyString(), anyString(), any());
    }

    @Test
    void connectSinAutenticacion_guardaComoAnonimo() {
        var sinAuth = new MensajeroHandler(conexiones, null, Clock.fixed(AHORA, ZoneOffset.UTC));

        assertThat(sinAuth.handleRequest(evento("$connect", null), null).getStatusCode()).isEqualTo(200);
        verify(conexiones).guardar("conn-1", MensajeroHandler.ANONIMO, AHORA);
    }

    @Test
    void disconnect_eliminaLaConexion() {
        assertThat(handler.handleRequest(evento("$disconnect", null), null).getStatusCode()).isEqualTo(200);
        verify(conexiones).eliminar("conn-1");
        verifyNoInteractions(validator);
    }

    @Test
    void default_seIgnora() {
        assertThat(handler.handleRequest(evento("$default", null), null).getStatusCode()).isEqualTo(200);
        verifyNoInteractions(conexiones, validator);
    }

    @Test
    void eventoInvalidoOErrorDeDynamo_noRevientanLaLambda() {
        assertThat(handler.handleRequest(null, null).getStatusCode()).isEqualTo(400);
        assertThat(handler.handleRequest(new APIGatewayV2WebSocketEvent(), null).getStatusCode()).isEqualTo(400);

        doThrow(new IllegalStateException("dynamo caido")).when(conexiones).eliminar("conn-1");
        assertThat(handler.handleRequest(evento("$disconnect", null), null).getStatusCode()).isEqualTo(500);
    }
}
