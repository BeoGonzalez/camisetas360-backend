package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.model.Accion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InteraccionEventoParserTest {

    private final InteraccionEventoParser parser = new InteraccionEventoParser();

    @Test
    void productoVistoEsUnaVistaDePeso1() {
        UUID id = UUID.randomUUID();
        var interaccion = parser.parse(evento(id, "PRODUCTO_VISTO", "11111111-1111-1111-1111-111111111111:AAAA", "CHI-LOC"))
                .orElseThrow();

        assertThat(interaccion.eventId()).isEqualTo(id);
        assertThat(interaccion.accion()).isEqualTo(Accion.VISTA);
        assertThat(interaccion.accion().peso()).isEqualTo(1);
        assertThat(interaccion.usuarioId()).isEqualTo("11111111-1111-1111-1111-111111111111:aaaa");
        assertThat(interaccion.producto().nombre()).isEqualTo("Camiseta Chile Local 2026");
        assertThat(interaccion.producto().precio()).isEqualTo(29990.0);
        assertThat(interaccion.origen()).isEqualTo("catalog");
        assertThat(interaccion.ocurridoEn()).isEqualTo(Instant.parse("2026-11-10T12:00:00Z"));
    }

    @Test
    void itemAgregadoEsCarritoDePeso2() {
        var interaccion = parser.parse(evento(UUID.randomUUID(), "ITEM_AGREGADO", "u:1", "CHI-LOC")).orElseThrow();

        assertThat(interaccion.accion()).isEqualTo(Accion.CARRITO);
        assertThat(interaccion.accion().peso()).isEqualTo(2);
    }

    @Test
    void camposDesconocidosSeIgnoran() {
        String json = evento(UUID.randomUUID(), "PRODUCTO_VISTO", "u:1", "CHI-LOC").replace("{\"eventId\"", "{\"extra\":1,\"eventId\"");
        assertThat(parser.parse(json)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ITEM_ELIMINADO", "ENVIO_ACTUALIZADO", "OTRO"})
    void tiposNoPonderadosSeDescartan(String tipo) {
        assertThat(parser.parse(evento(UUID.randomUUID(), tipo, "u:1", "CHI-LOC"))).isEmpty();
    }

    @Test
    void eventosInvalidosSeDescartan() {
        assertThat(parser.parse("no-json")).isEmpty();
        assertThat(parser.parse("null")).isEmpty();
        assertThat(parser.parse("{}")).isEmpty();
        assertThat(parser.parse(evento(UUID.randomUUID(), "PRODUCTO_VISTO", "", "CHI-LOC"))).isEmpty();
        assertThat(parser.parse(evento(UUID.randomUUID(), "PRODUCTO_VISTO", "u:1", "SKU CON ESPACIOS"))).isEmpty();
        assertThat(parser.parse(evento(UUID.randomUUID(), "PRODUCTO_VISTO", "u:1", "CHI-LOC").replace("\"version\":1", "\"version\":2")))
                .isEmpty();
        assertThat(parser.parse(evento(UUID.randomUUID(), "PRODUCTO_VISTO", "u:1", "CHI-LOC").replace("2026-11-10T12:00:00Z", "ayer")))
                .isEmpty();
        assertThat(parser.parse(evento(UUID.randomUUID(), "PRODUCTO_VISTO", "u:1", "CHI-LOC").replace("\"eventId\":", "\"otroId\":")))
                .isEmpty();
    }

    static String evento(UUID id, String tipo, String usuario, String sku) {
        return """
                {"eventId":"%s","eventType":"%s","version":1,"occurredAt":"2026-11-10T12:00:00Z","source":"catalog",
                 "usuarioId":"%s","producto":{"sku":"%s","nombre":"Camiseta Chile Local 2026","categoria":"Selecciones","precio":29990.0},
                 "cantidad":1}
                """.formatted(id, tipo, usuario, sku);
    }
}
