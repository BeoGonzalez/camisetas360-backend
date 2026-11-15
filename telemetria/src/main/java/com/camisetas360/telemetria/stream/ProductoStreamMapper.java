package com.camisetas360.telemetria.stream;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte los registros del stream (NEW_IMAGE) en {@link ProductoTelemetria}. Solo INSERT y MODIFY;
 * REMOVE se ignora. Si un mismo SKU aparece varias veces en el lote se conserva el ultimo estado.
 */
public final class ProductoStreamMapper {

    private ProductoStreamMapper() {
    }

    public static List<ProductoTelemetria> desde(DynamodbEvent event) {
        if (event == null || event.getRecords() == null) {
            return List.of();
        }
        Map<String, ProductoTelemetria> porSku = new LinkedHashMap<>();
        for (var record : event.getRecords()) {
            String tipo = record.getEventName();
            if (!"INSERT".equals(tipo) && !"MODIFY".equals(tipo)) {
                continue;
            }
            if (record.getDynamodb() == null) {
                continue;
            }
            var imagen = record.getDynamodb().getNewImage();
            String sku = texto(imagen, "sku");
            if (sku == null) {
                continue;
            }
            porSku.remove(sku); // reinsertar deja el SKU en el orden de su ultima modificacion
            porSku.put(sku, new ProductoTelemetria(
                    sku,
                    texto(imagen, "nombre"),
                    texto(imagen, "categoria"),
                    decimal(imagen, "precio"),
                    entero(imagen, "vistas"),
                    entero(imagen, "agregados"),
                    texto(imagen, "actualizadoEn")));
        }
        return new ArrayList<>(porSku.values());
    }

    private static String texto(Map<String, AttributeValue> imagen, String nombre) {
        if (imagen == null) return null;
        AttributeValue valor = imagen.get(nombre);
        return valor == null ? null : valor.getS();
    }

    private static Double decimal(Map<String, AttributeValue> imagen, String nombre) {
        AttributeValue valor = imagen.get(nombre);
        if (valor == null || valor.getN() == null) return null;
        try {
            return Double.valueOf(valor.getN());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long entero(Map<String, AttributeValue> imagen, String nombre) {
        AttributeValue valor = imagen.get(nombre);
        if (valor == null || valor.getN() == null) return 0;
        try {
            return new java.math.BigDecimal(valor.getN()).longValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
