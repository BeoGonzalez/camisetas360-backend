package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.messaging.OrderEventInbox;
import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.parser.OrderEventParser;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.OrderInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Inbox de order.created: guarda el evento una vez (idempotente por eventId) y registra cada linea
 * de la orden como interaccion COMPRA (peso 3). Todo ocurre en una transaccion: si algo falla, el
 * mensaje se reintenta y termina en la DLQ, en vez de perder la compra en silencio.
 */
@Service
public class DefaultOrderEventInboxService implements OrderEventInbox {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderEventInboxService.class);
    static final String ORIGEN = "orders";

    private final OrderEventParser parser;
    private final OrderInboxRepository repository;
    private final RecomendacionService recomendaciones;
    private final Clock clock;

    public DefaultOrderEventInboxService(OrderEventParser parser, OrderInboxRepository repository,
                                         RecomendacionService recomendaciones, Clock clock) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.recomendaciones = Objects.requireNonNull(recomendaciones, "recomendaciones cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    @Transactional
    public boolean accept(byte[] body) {
        ParsedOrderEvent parsed = parser.parse(body);
        String rawPayload = new String(body, StandardCharsets.UTF_8);
        if (!repository.saveIfAbsent(parsed.eventId(), parsed.orderId(), rawPayload)) {
            return false;
        }

        if (parsed.usuarioId() == null) {
            log.warn("order.created #{} sin tenantId/objectId: no se registran compras", parsed.orderId());
        } else {
            Instant ocurrido = parsed.occurredAt() != null ? parsed.occurredAt() : clock.instant();
            List<Interaccion> compras = new ArrayList<>();
            for (ParsedOrderEvent.Linea linea : parsed.lineas()) {
                compras.add(new Interaccion(
                        idDeLinea(parsed.eventId(), linea.sku()),
                        parsed.usuarioId(),
                        new ProductoFicha(linea.sku(), null, null, linea.unitPrice()),
                        Accion.COMPRA,
                        ORIGEN,
                        ocurrido));
            }
            // Todas las lineas juntas: un solo lock de usuario y un solo recalculo del Top 5.
            recomendaciones.registrarTodas(compras);
        }
        repository.markProcessed(parsed.eventId());
        return true;
    }

    /** Id determinista por (evento, sku): reprocesar la misma orden no duplica la compra. */
    static UUID idDeLinea(UUID eventId, String sku) {
        return UUID.nameUUIDFromBytes((eventId + ":" + sku).getBytes(StandardCharsets.UTF_8));
    }
}
