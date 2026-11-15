package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.Recomendacion;
import com.camisetas360.recommendation.repository.InteraccionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Logica de la guia: cada interaccion suma su peso al producto y el Top 5 del usuario son los
 * productos con mayor peso acumulado. A diferencia de la guia, la interaccion se guarda una sola
 * vez (en el consumidor), de forma idempotente, y el Top 5 se recalcula en la misma transaccion.
 *
 * <p>Orden de bloqueos, igual en todas las transacciones para que no haya deadlocks entre los
 * consumidores de Kafka y RabbitMQ: primero el lock del usuario, despues las filas de
 * {@code productos_conocidos} ordenadas por sku.
 */
@Service
public class RecomendacionService {

    private static final Logger log = LoggerFactory.getLogger(RecomendacionService.class);

    private final InteraccionRepository repository;

    public RecomendacionService(InteraccionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** @return false si la interaccion ya habia sido registrada (reentrega). */
    @Transactional
    public boolean registrar(Interaccion interaccion) {
        return registrarTodas(List.of(interaccion)) == 1;
    }

    /**
     * Registra en una transaccion varias interacciones del mismo usuario (p. ej. las lineas de una
     * orden) y recalcula su Top 5 una sola vez.
     *
     * @return cuantas interacciones eran nuevas
     */
    @Transactional
    public int registrarTodas(List<Interaccion> interacciones) {
        if (interacciones.isEmpty()) {
            return 0;
        }
        String usuarioId = interacciones.getFirst().usuarioId();
        if (interacciones.stream().anyMatch(i -> !i.usuarioId().equals(usuarioId))) {
            throw new IllegalArgumentException("Todas las interacciones deben ser del mismo usuario");
        }

        repository.bloquearUsuario(usuarioId);

        List<Interaccion> nuevas = new ArrayList<>();
        for (Interaccion interaccion : interacciones) {
            if (repository.guardarSiNoExiste(interaccion)) {
                nuevas.add(interaccion);
            } else {
                log.debug("Interaccion duplicada ignorada eventId={}", interaccion.eventId());
            }
        }
        if (nuevas.isEmpty()) {
            return 0;
        }

        repository.recalcularTop5(usuarioId);
        // Al final y por sku: las filas de productos populares se bloquean siempre en el mismo orden
        // y durante el menor tiempo posible.
        nuevas.stream()
                .sorted(Comparator.comparing(Interaccion::sku))
                .forEach(i -> repository.actualizarProducto(i.producto()));
        return nuevas.size();
    }

    @Transactional(readOnly = true)
    public List<Recomendacion> top5(String usuarioId) {
        return repository.top5(usuarioId);
    }
}
