package com.camisetas360.analytics.messaging;

import com.camisetas360.analytics.model.AlertaFraude;
import com.camisetas360.analytics.model.InteraccionEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valida el contrato de los eventos entrantes. Un mensaje invalido se descarta (y se registra)
 * en vez de reintentarse indefinidamente, para no bloquear la particion.
 */
@Component
public class EventoParser {

    private static final Logger log = LoggerFactory.getLogger(EventoParser.class);
    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> CON_PRODUCTO = Set.of(
            InteraccionEvento.PRODUCTO_VISTO, InteraccionEvento.ITEM_AGREGADO, InteraccionEvento.ITEM_ELIMINADO);

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public Optional<InteraccionEvento> interaccion(String payload) {
        InteraccionEvento evento;
        try {
            evento = json.readValue(payload, InteraccionEvento.class);
        } catch (JacksonException | IllegalArgumentException invalido) {
            log.warn("Evento de interaccion con JSON invalido descartado: {}", invalido.getMessage());
            return Optional.empty();
        }
        String motivo = validar(evento);
        if (motivo != null) {
            log.warn("Evento de interaccion descartado eventId={}: {}", evento == null ? null : evento.eventId(), motivo);
            return Optional.empty();
        }
        return Optional.of(evento);
    }

    public Optional<AlertaFraude> fraude(String payload) {
        try {
            JsonNode node = json.readTree(payload);
            if (!"PAGO_FRAUDE_DETECTADO".equals(texto(node, "eventType"))) {
                return Optional.empty();
            }
            Double monto = node.hasNonNull("monto") && node.get("monto").isNumber() ? node.get("monto").asDouble() : null;
            return Optional.of(new AlertaFraude(texto(node, "ordenId"), monto, texto(node, "ultimos4"), texto(node, "motivo")));
        } catch (JacksonException invalido) {
            log.warn("Evento de fraude con JSON invalido descartado: {}", invalido.getOriginalMessage());
            return Optional.empty();
        }
    }

    private static String validar(InteraccionEvento evento) {
        if (evento == null) return "evento vacio";
        if (evento.eventId() == null) return "falta eventId";
        if (evento.version() != InteraccionEvento.VERSION) return "version no soportada " + evento.version();
        if (evento.usuarioId() == null || evento.usuarioId().isBlank() || evento.usuarioId().length() > 200) {
            return "usuarioId invalido";
        }
        try {
            Instant.parse(evento.occurredAt());
        } catch (DateTimeParseException | NullPointerException invalid) {
            return "occurredAt invalido";
        }
        String tipo = evento.eventType();
        if (CON_PRODUCTO.contains(tipo)) {
            if (evento.producto() == null || evento.producto().sku() == null || !SKU.matcher(evento.producto().sku()).matches()) {
                return "producto invalido";
            }
            if (!InteraccionEvento.PRODUCTO_VISTO.equals(tipo) && (evento.cantidad() == null || evento.cantidad() < 1)) {
                return "cantidad invalida";
            }
            return null;
        }
        if (InteraccionEvento.ENVIO_ACTUALIZADO.equals(tipo)) {
            return evento.envio() == null || evento.envio().metodo() == null ? "envio invalido" : null;
        }
        return "eventType desconocido " + tipo;
    }

    private static String texto(JsonNode node, String campo) {
        return node.hasNonNull(campo) ? node.get(campo).asString() : null;
    }
}
