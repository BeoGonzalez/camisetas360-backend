package com.camisetas360.telemetria;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.camisetas360.telemetria.auth.EntraTokenValidator;
import com.camisetas360.telemetria.auth.TokenInvalidoException;
import com.camisetas360.telemetria.conexiones.ConexionesRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Clock;
import java.util.Map;

/**
 * Lambda "Mensajero" (handler {@code com.camisetas360.telemetria.MensajeroHandler::handleRequest}).
 * Se integra con las rutas {@code $connect}, {@code $disconnect} y {@code $default} del API WebSocket.
 *
 * <p>{@code $connect}: el navegador no puede enviar el header Authorization en un WebSocket, asi que el
 * access token de Entra llega como query string: {@code wss://.../prod?token=<access_token>}. Si no es
 * valido se responde 401 y API Gateway rechaza la conexion. {@code $disconnect} borra el registro.
 */
public class MensajeroHandler implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    static final String ANONIMO = "anonimo";

    private final ConexionesRepository conexiones;
    private final EntraTokenValidator validator; // null si WS_AUTH_ENABLED=false
    private final Clock clock;

    /** Constructor usado por el runtime de Lambda: se ejecuta una vez por contenedor. */
    public MensajeroHandler() {
        this(Configuracion.desdeEntorno(), DynamoDbClient.create());
    }

    private MensajeroHandler(Configuracion config, DynamoDbClient dynamo) {
        this(new ConexionesRepository(dynamo, config.tablaConexiones(), config.ttlConexion()),
                config.autenticacionHabilitada()
                        ? EntraTokenValidator.remoto(config.issuer(), config.audience(), config.jwksUri())
                        : null,
                Clock.systemUTC());
    }

    MensajeroHandler(ConexionesRepository conexiones, EntraTokenValidator validator, Clock clock) {
        this.conexiones = conexiones;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        var requestContext = event == null ? null : event.getRequestContext();
        if (requestContext == null || requestContext.getConnectionId() == null) {
            return respuesta(400, "Evento WebSocket invalido");
        }
        String connectionId = requestContext.getConnectionId();
        String ruta = requestContext.getRouteKey() == null ? "" : requestContext.getRouteKey();
        try {
            return switch (ruta) {
                case "$connect" -> conectar(connectionId, event.getQueryStringParameters(), context);
                case "$disconnect" -> {
                    conexiones.eliminar(connectionId);
                    log(context, "Conexion cerrada " + connectionId);
                    yield respuesta(200, "Desconectado");
                }
                // El dashboard es de solo lectura: los mensajes del cliente se ignoran.
                default -> respuesta(200, "Sin acciones disponibles");
            };
        } catch (RuntimeException e) {
            log(context, "Error en ruta " + ruta + " para " + connectionId + ": " + e.getClass().getSimpleName());
            return respuesta(500, "Error interno");
        }
    }

    private APIGatewayV2WebSocketResponse conectar(String connectionId, Map<String, String> query, Context context) {
        String usuario = ANONIMO;
        if (validator != null) {
            try {
                usuario = validator.validar(query == null ? null : query.get("token"));
            } catch (TokenInvalidoException e) {
                // Nunca se registra el token, solo el motivo.
                log(context, "Conexion rechazada " + connectionId + ": " + e.getMessage());
                return respuesta(401, "No autorizado");
            }
        }
        conexiones.guardar(connectionId, usuario, clock.instant());
        log(context, "Cliente conectado " + connectionId);
        return respuesta(200, "Conectado");
    }

    private static APIGatewayV2WebSocketResponse respuesta(int status, String body) {
        var response = new APIGatewayV2WebSocketResponse();
        response.setStatusCode(status);
        response.setBody(body);
        return response;
    }

    private static void log(Context context, String mensaje) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(mensaje);
        }
    }
}
