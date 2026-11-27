package com.camisetas360.telemetria;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Configuracion de las Lambdas, leida de variables de entorno (se definen en la consola de Lambda).
 *
 * <ul>
 *   <li>{@code TABLA_CONEXIONES}: tabla DynamoDB de conexiones WebSocket (default {@code ConexionesWebSocket}).</li>
 *   <li>{@code WEBSOCKET_API_ENDPOINT}: URL https de la etapa del API WebSocket
 *       ({@code https://<api-id>.execute-api.us-east-1.amazonaws.com/<etapa>}). Solo la usa la Lambda Stream.</li>
 *   <li>{@code ENTRA_ISSUER_URI} y {@code ENTRA_AUDIENCE}: los mismos valores que usan los microservicios.</li>
 *   <li>{@code ENTRA_JWKS_URI}: opcional; si falta se obtiene del documento OIDC del issuer.</li>
 *   <li>{@code WS_AUTH_ENABLED}: {@code true} por defecto. {@code false} acepta conexiones sin token
 *       (solo para probar con wscat; nunca en una demo con datos reales).</li>
 *   <li>{@code CONEXION_TTL_HORAS}: vida maxima del registro de conexion (default 2, el maximo de API Gateway).</li>
 *   <li>{@code COGNITO_ISSUER_URI} y {@code COGNITO_CLIENT_IDS} (separados por coma): opcionales; si estan, el
 *       dashboard acepta tambien access tokens del user pool (grupos ADMIN o CATALOG_MANAGER).</li>
 * </ul>
 */
public record Configuracion(
        String tablaConexiones,
        String webSocketApiEndpoint,
        String issuer,
        String audience,
        String jwksUri,
        boolean autenticacionHabilitada,
        Duration ttlConexion,
        String cognitoIssuer,
        List<String> cognitoClientIds) {

    public boolean entraConfigurado() {
        return !issuer.isEmpty() && !audience.isEmpty();
    }

    public boolean cognitoConfigurado() {
        return !cognitoIssuer.isEmpty();
    }

    public static Configuracion desdeEntorno() {
        return desde(System.getenv());
    }

    public static Configuracion desde(Map<String, String> env) {
        return new Configuracion(
                valor(env, "TABLA_CONEXIONES", "ConexionesWebSocket"),
                valor(env, "WEBSOCKET_API_ENDPOINT", ""),
                valor(env, "ENTRA_ISSUER_URI", ""),
                valor(env, "ENTRA_AUDIENCE", ""),
                valor(env, "ENTRA_JWKS_URI", ""),
                !"false".equalsIgnoreCase(valor(env, "WS_AUTH_ENABLED", "true")),
                Duration.ofHours(Long.parseLong(valor(env, "CONEXION_TTL_HORAS", "2"))),
                valor(env, "COGNITO_ISSUER_URI", ""),
                Arrays.stream(valor(env, "COGNITO_CLIENT_IDS", "").split(","))
                        .map(String::trim).filter(id -> !id.isEmpty()).toList());
    }

    private static String valor(Map<String, String> env, String nombre, String porDefecto) {
        String valor = env.get(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor.trim();
    }
}
