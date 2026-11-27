package com.camisetas360.telemetria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.camisetas360.telemetria.cognito.CognitoLogin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CognitoLoginHandlerTest {

    final CognitoLogin login = mock(CognitoLogin.class);
    final CognitoLoginHandler handler = new CognitoLoginHandler(login);

    static APIGatewayV2HTTPEvent evento(String metodo, String ruta, String body, boolean base64) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(ruta)
                .withBody(body)
                .withIsBase64Encoded(base64)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(metodo).build())
                        .build())
                .build();
    }

    @Test
    void enrutaPorElUltimoSegmentoYSerializaLaRespuesta() {
        when(login.ejecutar(eq("login"), anyMap()))
                .thenReturn(new CognitoLogin.Resultado(200, Map.of("accessToken", "at")));

        var r = handler.handleRequest(evento("POST", "/api/v1/auth/cognito/login",
                "{\"email\":\"a@b.cl\",\"password\":\"x\"}", false), null);

        assertThat(r.getStatusCode()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("{\"accessToken\":\"at\"}");
        assertThat(r.getHeaders()).containsEntry("Cache-Control", "no-store");
        verify(login).ejecutar("login", Map.of("email", "a@b.cl", "password", "x"));
    }

    @Test
    void aceptaBodyEnBase64YBodyVacio() {
        when(login.ejecutar(any(), anyMap())).thenReturn(new CognitoLogin.Resultado(400, Map.of("error", "x")));
        String body = Base64.getEncoder().encodeToString("{\"refreshToken\":\"r\"}".getBytes(StandardCharsets.UTF_8));

        handler.handleRequest(evento("POST", "/api/v1/auth/cognito/refresh/", body, true), null);
        handler.handleRequest(evento("POST", "/api/v1/auth/cognito/register", null, false), null);

        verify(login).ejecutar("refresh", Map.of("refreshToken", "r"));
        verify(login).ejecutar("register", Map.of());
    }

    @Test
    void bodyInvalidoResponde400() {
        assertThat(handler.handleRequest(evento("POST", "/api/v1/auth/cognito/login", "[1,2]", false), null)
                .getStatusCode()).isEqualTo(400);
        assertThat(handler.handleRequest(evento("POST", "/api/v1/auth/cognito/login", "null", false), null)
                .getStatusCode()).isEqualTo(400);
        assertThat(handler.handleRequest(evento("POST", "/api/v1/auth/cognito/login", "%%%", true), null)
                .getStatusCode()).isEqualTo(400);
        verifyNoInteractions(login);
    }

    @Test
    void otroMetodoOtraRutaOEventoVacioResponden404() {
        assertThat(handler.handleRequest(evento("GET", "/api/v1/auth/cognito/login", null, false), null)
                .getStatusCode()).isEqualTo(404);
        assertThat(handler.handleRequest(evento("POST", "/api/v1/orders", "{}", false), null)
                .getStatusCode()).isEqualTo(404);
        assertThat(handler.handleRequest(null, null).getStatusCode()).isEqualTo(404);
        verifyNoInteractions(login);
    }
}
