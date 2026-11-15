package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Traduce un evento de Kafka a una {@link Interaccion}. Lee el JSON como texto con tipos propios
 * (no usa JsonDeserializer con trusted.packages="*", que permite instanciar clases arbitrarias).
 * Un mensaje invalido se descarta y se registra en vez de reintentarse para siempre.
 */
@Component
public class InteraccionEventoParser {

    private static final Logger log = LoggerFactory.getLogger(InteraccionEventoParser.class);
    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public Optional<Interaccion> parse(String payload) {
        InteraccionEvento evento;
        try {
            evento = json.readValue(payload, InteraccionEvento.class);
        } catch (JacksonException | IllegalArgumentException invalido) {
            log.warn("Evento de interaccion con JSON invalido descartado: {}", invalido.getMessage());
            return Optional.empty();
        }
        if (evento == null) {
            log.warn("Evento de interaccion vacio descartado");
            return Optional.empty();
        }

        Accion accion = switch (String.valueOf(evento.eventType())) {
            case InteraccionEvento.PRODUCTO_VISTO -> Accion.VISTA;
            case InteraccionEvento.ITEM_AGREGADO -> Accion.CARRITO;
            default -> null;
        };
        String motivo = validar(evento, accion);
        if (motivo != null) {
            log.warn("Evento de interaccion descartado eventId={}: {}", evento.eventId(), motivo);
            return Optional.empty();
        }

        var producto = evento.producto();
        return Optional.of(new Interaccion(
                evento.eventId(),
                evento.usuarioId().toLowerCase(Locale.ROOT),
                new ProductoFicha(producto.sku(), producto.nombre(), producto.categoria(), producto.precio()),
                accion,
                evento.source() == null || evento.source().isBlank() ? "kafka" : recortar(evento.source(), 32),
                Instant.parse(evento.occurredAt())));
    }

    private static String validar(InteraccionEvento evento, Accion accion) {
        if (accion == null) return "eventType no soportado " + evento.eventType();
        if (evento.eventId() == null) return "falta eventId";
        if (evento.version() != InteraccionEvento.VERSION) return "version no soportada " + evento.version();
        if (evento.usuarioId() == null || evento.usuarioId().isBlank() || evento.usuarioId().length() > 200) {
            return "usuarioId invalido";
        }
        try {
            Instant.parse(evento.occurredAt());
        } catch (DateTimeParseException | NullPointerException invalido) {
            return "occurredAt invalido";
        }
        if (evento.producto() == null || evento.producto().sku() == null
                || !SKU.matcher(evento.producto().sku()).matches()) {
            return "producto invalido";
        }
        return null;
    }

    private static String recortar(String valor, int max) {
        return valor.length() <= max ? valor : valor.substring(0, max);
    }
}
