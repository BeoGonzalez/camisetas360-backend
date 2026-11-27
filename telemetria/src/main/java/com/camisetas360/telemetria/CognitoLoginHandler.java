package com.camisetas360.telemetria;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.camisetas360.telemetria.cognito.CognitoLogin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Lambda "camisetas360-cognito-login" (handler {@code com.camisetas360.telemetria.CognitoLoginHandler::handleRequest}).
 * Integracion AWS_PROXY de las HTTP API (payload 2.0), rutas publicas:
 * <pre>
 *   POST /api/v1/auth/cognito/login     {"email","password"}           -> 200 {accessToken,idToken,refreshToken,expiresIn,tokenType}
 *   POST /api/v1/auth/cognito/refresh   {"refreshToken"}                -> 200 tokens
 *   POST /api/v1/auth/cognito/register  {"email","password","name"?}    -> 201 {userSub,email,confirmed}
 *   POST /api/v1/auth/cognito/confirm   {"email","code"}                -> 200 (solo con AUTO_CONFIRM=false)
 * </pre>
 * Variables: {@code USER_POOL_ID}, {@code CLIENT_ID}, {@code DEFAULT_GROUP} (CUSTOMER) y {@code AUTO_CONFIRM}
 * (true: confirma el registro sin codigo por email; AWS Academy limita los correos de Cognito).
 */
public class CognitoLoginHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CognitoLogin login;

    /** Constructor del runtime de Lambda: una vez por contenedor. */
    public CognitoLoginHandler() {
        this(new CognitoLogin(
                CognitoIdentityProviderClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("USER_POOL_ID"), System.getenv("CLIENT_ID"),
                valor("DEFAULT_GROUP", "CUSTOMER"), !"false".equalsIgnoreCase(valor("AUTO_CONFIRM", "true"))));
    }

    CognitoLoginHandler(CognitoLogin login) {
        this.login = login;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String ruta = event == null || event.getRawPath() == null ? "" : event.getRawPath();
        String metodo = event == null || event.getRequestContext() == null || event.getRequestContext().getHttp() == null
                ? "" : event.getRequestContext().getHttp().getMethod();
        String accion = ruta.replaceAll("/+$", "");
        accion = accion.substring(accion.lastIndexOf('/') + 1);
        if (!"POST".equalsIgnoreCase(metodo) || !ruta.contains("/auth/cognito/")) {
            return respuesta(CognitoLogin.Resultado.error(404, "Ruta no encontrada"));
        }
        Map<String, Object> datos;
        try {
            datos = leer(event);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return respuesta(CognitoLogin.Resultado.error(400, "El body debe ser un objeto JSON"));
        }
        var resultado = login.ejecutar(accion, datos);
        if (context != null && context.getLogger() != null && resultado.status() >= 400) {
            // Nunca se registran credenciales ni tokens, solo la accion y el status.
            context.getLogger().log("Cognito " + accion + " -> " + resultado.status());
        }
        return respuesta(resultado);
    }

    private static Map<String, Object> leer(APIGatewayV2HTTPEvent event) throws JsonProcessingException {
        String body = event.getBody();
        if (body == null || body.isBlank()) return Map.of();
        if (event.getIsBase64Encoded()) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        Map<String, Object> datos = JSON.readValue(body, new TypeReference<>() { });
        if (datos == null) throw new IllegalArgumentException("body");
        return datos;
    }

    private static APIGatewayV2HTTPResponse respuesta(CognitoLogin.Resultado resultado) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(resultado.status())
                    .withHeaders(Map.of("Content-Type", "application/json", "Cache-Control", "no-store"))
                    .withBody(JSON.writeValueAsString(resultado.cuerpo()))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String valor(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor.trim();
    }
}
