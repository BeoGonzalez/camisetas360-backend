package com.camisetas360.analytics.messaging;

import com.camisetas360.analytics.model.AlertaFraude;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventoParserTest {

    private final EventoParser parser = new EventoParser();

    @Test
    void interaccion_aceptaElContratoV1EIgnoraCamposDesconocidos() {
        var evento = parser.interaccion("""
                {"eventId":"c8e76a6b-2856-4b89-a477-85b88c0378bc","eventType":"ITEM_AGREGADO","version":1,
                 "occurredAt":"2026-11-10T12:00:00Z","source":"carrito","usuarioId":"t:o",
                 "producto":{"sku":"SKU-A","nombre":"Camiseta","categoria":"Liga","precio":19.5},
                 "cantidad":2,"envio":null,"campoNuevo":"x"}
                """).orElseThrow();

        assertThat(evento.eventType()).isEqualTo("ITEM_AGREGADO");
        assertThat(evento.producto().precio()).isEqualTo(19.5);
        assertThat(evento.cantidad()).isEqualTo(2);
    }

    @Test
    void interaccion_aceptaEnvio() {
        assertThat(parser.interaccion("""
                {"eventId":"c8e76a6b-2856-4b89-a477-85b88c0378bc","eventType":"ENVIO_ACTUALIZADO","version":1,
                 "occurredAt":"2026-11-10T12:00:00Z","source":"carrito","usuarioId":"t:o",
                 "envio":{"metodo":"EXPRESS","region":"Metropolitana"}}
                """)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "no-es-json",
            "{}",
            // version no soportada
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"PRODUCTO_VISTO\",\"version\":2,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\"t:o\",\"producto\":{\"sku\":\"A\"}}",
            // tipo desconocido
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"CUPON\",\"version\":1,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\"t:o\"}",
            // falta producto
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"PRODUCTO_VISTO\",\"version\":1,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\"t:o\"}",
            // sku invalido
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"PRODUCTO_VISTO\",\"version\":1,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\"t:o\",\"producto\":{\"sku\":\"A B\"}}",
            // cantidad requerida en el carrito
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"ITEM_AGREGADO\",\"version\":1,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\"t:o\",\"producto\":{\"sku\":\"A\"}}",
            // fecha invalida
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"PRODUCTO_VISTO\",\"version\":1,\"occurredAt\":\"ayer\",\"usuarioId\":\"t:o\",\"producto\":{\"sku\":\"A\"}}",
            // sin usuario
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"PRODUCTO_VISTO\",\"version\":1,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\" \",\"producto\":{\"sku\":\"A\"}}",
            // envio sin metodo
            "{\"eventId\":\"c8e76a6b-2856-4b89-a477-85b88c0378bc\",\"eventType\":\"ENVIO_ACTUALIZADO\",\"version\":1,\"occurredAt\":\"2026-11-10T12:00:00Z\",\"usuarioId\":\"t:o\",\"envio\":{}}"
    })
    void interaccion_descartaMensajesInvalidos(String payload) {
        assertThat(parser.interaccion(payload)).isEmpty();
    }

    @Test
    void fraude_extraeSoloDatosNoSensibles() {
        var alerta = parser.fraude("""
                {"eventId":"99887766-5544-3322-1100-aabbccddeeff","eventType":"PAGO_FRAUDE_DETECTADO","version":1,
                 "ordenId":"a1a2","pagoId":"p1","monto":39990.00,"estado":"BLOQUEADO_FRAUDE",
                 "tarjetaHash":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                 "ultimos4":"9012","motivo":"4 intentos"}
                """).orElseThrow();

        assertThat(alerta).isEqualTo(new AlertaFraude("a1a2", 39990.0, "9012", "4 intentos"));
    }

    @Test
    void fraude_ignoraOtrosTiposYJsonInvalido() {
        assertThat(parser.fraude("{\"eventType\":\"PAGO_AUTORIZADO\"}")).isEmpty();
        assertThat(parser.fraude("{")).isEmpty();
    }
}
