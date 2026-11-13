package com.camisetas360.telemetria;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.camisetas360.telemetria.conexiones.ConexionesRepository;
import com.camisetas360.telemetria.stream.Difusor;
import com.camisetas360.telemetria.stream.ProductoStreamMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Clock;

/**
 * Lambda "Stream" (handler {@code com.camisetas360.telemetria.TelemetriaStreamHandler::handleRequest}).
 * Desencadenador: DynamoDB Streams de la tabla {@code camisetas360-analytics-productos}.
 *
 * <p>Es telemetria en vivo: si el envio falla se registra y el lote no se reintenta (reintentar
 * bloquearia el shard del stream y los datos viejos ya no sirven en el dashboard).
 */
public class TelemetriaStreamHandler implements RequestHandler<DynamodbEvent, Void> {

    private final Difusor difusor;

    public TelemetriaStreamHandler() {
        this(Configuracion.desdeEntorno());
    }

    private TelemetriaStreamHandler(Configuracion config) {
        this(crearDifusor(config));
    }

    TelemetriaStreamHandler(Difusor difusor) {
        this.difusor = difusor;
    }

    private static Difusor crearDifusor(Configuracion config) {
        String endpoint = config.webSocketApiEndpoint();
        if (endpoint.isBlank()) {
            return null;
        }
        if (endpoint.startsWith("wss://")) {
            // Error tipico: se pega la URL wss://. La API de administracion usa https:// con la misma ruta.
            endpoint = "https://" + endpoint.substring("wss://".length());
        }
        var api = ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(endpoint))
                .build();
        var conexiones = new ConexionesRepository(DynamoDbClient.create(), config.tablaConexiones(), config.ttlConexion());
        return new Difusor(api, conexiones, new ObjectMapper(), Clock.systemUTC());
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        var productos = ProductoStreamMapper.desde(event);
        if (productos.isEmpty()) {
            return null;
        }
        if (difusor == null) {
            log(context, "Falta la variable de entorno WEBSOCKET_API_ENDPOINT: no se envia la telemetria");
            return null;
        }
        try {
            var resultado = difusor.difundir(productos, mensaje -> log(context, mensaje));
            log(context, "Telemetria: " + productos.size() + " productos -> " + resultado.enviados()
                    + " conexiones (cerradas " + resultado.cerradas() + ", fallidas " + resultado.fallidos() + ")");
        } catch (RuntimeException e) {
            log(context, "Error difundiendo telemetria: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        return null;
    }

    private static void log(Context context, String mensaje) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(mensaje);
        }
    }
}
