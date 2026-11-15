package com.camisetas360.telemetria.stream;

import com.camisetas360.telemetria.conexiones.ConexionesRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Envia el lote a todas las conexiones activas con {@code postToConnection}. Las conexiones que
 * API Gateway reporta como cerradas (410 Gone) se borran de la tabla. Mensaje enviado:
 * <pre>{"tipo":"productos.actualizados","generadoEn":"...","productos":[{...}]}</pre>
 */
public class Difusor {

    public static final String TIPO = "productos.actualizados";
    /** API Gateway WebSocket admite mensajes de hasta 128 KB: se envian bloques chicos. */
    static final int PRODUCTOS_POR_MENSAJE = 50;

    private final ApiGatewayManagementApiClient api;
    private final ConexionesRepository conexiones;
    private final ObjectMapper json;
    private final Clock clock;

    public Difusor(ApiGatewayManagementApiClient api, ConexionesRepository conexiones, ObjectMapper json, Clock clock) {
        this.api = api;
        this.conexiones = conexiones;
        this.json = json;
        this.clock = clock;
    }

    public Resultado difundir(List<ProductoTelemetria> productos, Consumer<String> log) {
        if (productos.isEmpty()) {
            return new Resultado(0, 0, 0);
        }
        List<String> destinos = conexiones.activas(clock.instant());
        if (destinos.isEmpty()) {
            return new Resultado(0, 0, 0);
        }
        List<SdkBytes> mensajes = mensajes(productos);
        int enviados = 0;
        int cerradas = 0;
        int fallidos = 0;
        for (String connectionId : destinos) {
            try {
                for (SdkBytes mensaje : mensajes) {
                    api.postToConnection(r -> r.connectionId(connectionId).data(mensaje));
                }
                enviados++;
            } catch (GoneException cerrada) {
                cerradas++;
                try {
                    conexiones.eliminar(connectionId);
                } catch (RuntimeException e) {
                    log.accept("No se pudo borrar la conexion cerrada " + connectionId + ": " + e.getMessage());
                }
            } catch (RuntimeException e) {
                fallidos++;
                log.accept("Error enviando a " + connectionId + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        return new Resultado(enviados, cerradas, fallidos);
    }

    private List<SdkBytes> mensajes(List<ProductoTelemetria> productos) {
        List<SdkBytes> mensajes = new ArrayList<>();
        String generadoEn = clock.instant().toString();
        for (int i = 0; i < productos.size(); i += PRODUCTOS_POR_MENSAJE) {
            var bloque = productos.subList(i, Math.min(i + PRODUCTOS_POR_MENSAJE, productos.size()));
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("tipo", TIPO);
            cuerpo.put("generadoEn", generadoEn);
            cuerpo.put("productos", bloque);
            try {
                mensajes.add(SdkBytes.fromUtf8String(json.writeValueAsString(cuerpo)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("No se pudo serializar la telemetria", e);
            }
        }
        return mensajes;
    }

    public record Resultado(int enviados, int cerradas, int fallidos) {
    }
}
