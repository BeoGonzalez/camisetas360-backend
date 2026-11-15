package com.camisetas360.telemetria.stream;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductoStreamMapperTest {

    static DynamodbEvent.DynamodbStreamRecord registro(String tipo, Map<String, AttributeValue> imagen) {
        var stream = new StreamRecord();
        stream.setNewImage(imagen);
        var record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(tipo);
        record.setDynamodb(stream);
        return record;
    }

    static Map<String, AttributeValue> producto(String sku, String vistas, String agregados) {
        Map<String, AttributeValue> imagen = new HashMap<>();
        imagen.put("sku", new AttributeValue().withS(sku));
        imagen.put("nombre", new AttributeValue().withS("Camiseta " + sku));
        imagen.put("categoria", new AttributeValue().withS("Selecciones"));
        imagen.put("precio", new AttributeValue().withN("27990"));
        imagen.put("vistas", new AttributeValue().withN(vistas));
        imagen.put("agregados", new AttributeValue().withN(agregados));
        imagen.put("actualizadoEn", new AttributeValue().withS("2026-11-12T10:00:00Z"));
        return imagen;
    }

    static DynamodbEvent evento(DynamodbEvent.DynamodbStreamRecord... registros) {
        var event = new DynamodbEvent();
        event.setRecords(List.of(registros));
        return event;
    }

    @Test
    void insertYModify_seMapeanConTodosLosCampos() {
        var productos = ProductoStreamMapper.desde(evento(
                registro("INSERT", producto("CHI-LOC", "1", "0")),
                registro("MODIFY", producto("ARG-LOC", "5", "2"))));

        assertThat(productos).containsExactly(
                new ProductoTelemetria("CHI-LOC", "Camiseta CHI-LOC", "Selecciones", 27990.0, 1, 0, "2026-11-12T10:00:00Z"),
                new ProductoTelemetria("ARG-LOC", "Camiseta ARG-LOC", "Selecciones", 27990.0, 5, 2, "2026-11-12T10:00:00Z"));
    }

    @Test
    void mismoSkuEnElLote_quedaElUltimoEstado() {
        var productos = ProductoStreamMapper.desde(evento(
                registro("MODIFY", producto("A", "1", "0")),
                registro("MODIFY", producto("B", "1", "0")),
                registro("MODIFY", producto("A", "2", "1"))));

        assertThat(productos).extracting(ProductoTelemetria::sku).containsExactly("B", "A");
        assertThat(productos.get(1).vistas()).isEqualTo(2);
    }

    @Test
    void removeSinImagenOSinSku_seIgnoran() {
        Map<String, AttributeValue> sinSku = producto("X", "1", "0");
        sinSku.remove("sku");
        var sinStream = new DynamodbEvent.DynamodbStreamRecord();
        sinStream.setEventName("MODIFY");

        var productos = ProductoStreamMapper.desde(evento(
                registro("REMOVE", producto("A", "1", "0")),
                registro("MODIFY", null),
                registro("INSERT", sinSku),
                sinStream));

        assertThat(productos).isEmpty();
        assertThat(ProductoStreamMapper.desde(null)).isEmpty();
        assertThat(ProductoStreamMapper.desde(new DynamodbEvent())).isEmpty();
    }

    @Test
    void camposOpcionalesFaltantes_quedanNulosOCero() {
        Map<String, AttributeValue> minimo = new HashMap<>();
        minimo.put("sku", new AttributeValue().withS("MIN"));
        minimo.put("precio", new AttributeValue().withN("no-numero"));

        assertThat(ProductoStreamMapper.desde(evento(registro("INSERT", minimo))))
                .containsExactly(new ProductoTelemetria("MIN", null, null, null, 0, 0, null));
    }
}
