package com.camisetas360.telemetria;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.camisetas360.telemetria.stream.Difusor;
import com.camisetas360.telemetria.stream.ProductoTelemetria;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelemetriaStreamHandlerTest {

    final Difusor difusor = mock(Difusor.class);
    final TelemetriaStreamHandler handler = new TelemetriaStreamHandler(difusor);

    static DynamodbEvent eventoDe(String tipo, String sku) {
        var stream = new StreamRecord();
        stream.setNewImage(Map.of("sku", new AttributeValue().withS(sku), "vistas", new AttributeValue().withN("4")));
        var record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(tipo);
        record.setDynamodb(stream);
        var event = new DynamodbEvent();
        event.setRecords(List.of(record));
        return event;
    }

    @Test
    void modify_seDifundeComoProducto() {
        when(difusor.difundir(anyList(), any())).thenReturn(new Difusor.Resultado(1, 0, 0));

        handler.handleRequest(eventoDe("MODIFY", "CHI-LOC"), null);

        verify(difusor).difundir(
                org.mockito.ArgumentMatchers.eq(List.of(new ProductoTelemetria("CHI-LOC", null, null, null, 4, 0, null))),
                any());
    }

    @Test
    void soloRemove_noLlamaAlDifusor() {
        handler.handleRequest(eventoDe("REMOVE", "CHI-LOC"), null);
        verifyNoInteractions(difusor);
    }

    @Test
    void sinEndpointOErrorAlDifundir_noLanzaExcepcion() {
        var sinEndpoint = new TelemetriaStreamHandler((Difusor) null);
        assertThatCode(() -> sinEndpoint.handleRequest(eventoDe("MODIFY", "A"), null)).doesNotThrowAnyException();

        when(difusor.difundir(anyList(), any())).thenThrow(new IllegalStateException("scan fallo"));
        assertThatCode(() -> handler.handleRequest(eventoDe("MODIFY", "A"), null)).doesNotThrowAnyException();
    }
}
