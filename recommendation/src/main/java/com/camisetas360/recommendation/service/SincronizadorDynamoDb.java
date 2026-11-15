package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.InteraccionPendiente;
import com.camisetas360.recommendation.repository.DynamoInteraccionRepository;
import com.camisetas360.recommendation.repository.JdbcSincronizacionDynamoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Copia a DynamoDB las interacciones registradas en PostgreSQL (outbox, migracion V5).
 * Lotes de 25 con BatchWriteItem; lo que falla se pospone con backoff exponencial (5 s .. 5 min) y
 * se reintenta sin limite: credenciales vencidas de AWS Academy o una caida de DynamoDB solo
 * retrasan la copia, no la pierden.
 */
@Component
@ConditionalOnProperty(name = "app.dynamodb.enabled", havingValue = "true", matchIfMissing = true)
public class SincronizadorDynamoDb {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorDynamoDb.class);
    static final String METRICA = "recommendation.dynamodb.interacciones";

    private final JdbcSincronizacionDynamoRepository outbox;
    private final DynamoInteraccionRepository dynamo;
    private final TransactionTemplate transaccion;
    private final MeterRegistry metrics;
    private final int maxLotesPorCiclo;

    public SincronizadorDynamoDb(JdbcSincronizacionDynamoRepository outbox, DynamoInteraccionRepository dynamo,
                                 PlatformTransactionManager transacciones, MeterRegistry metrics,
                                 @Value("${app.dynamodb.max-lotes-por-ciclo:40}") int maxLotesPorCiclo) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dynamo = Objects.requireNonNull(dynamo, "dynamo");
        this.transaccion = new TransactionTemplate(transacciones);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.maxLotesPorCiclo = maxLotesPorCiclo;
    }

    @Scheduled(fixedDelayString = "${app.dynamodb.poll-ms:2000}")
    public void sincronizar() {
        try {
            // Vacia el backlog (p. ej. el backfill inicial) sin monopolizar el scheduler.
            for (int lote = 0; lote < maxLotesPorCiclo; lote++) {
                Boolean seguir = transaccion.execute(status -> enviarLote());
                if (!Boolean.TRUE.equals(seguir)) break;
            }
        } catch (RuntimeException fallo) {
            log.warn("Sincronizacion con DynamoDB fallo; las interacciones siguen pendientes", fallo);
        }
    }

    /** @return true si el lote estaba lleno y se envio completo (puede haber mas pendientes). */
    boolean enviarLote() {
        List<InteraccionPendiente> lote = outbox.bloquearPendientes(DynamoInteraccionRepository.LOTE_MAXIMO);
        if (lote.isEmpty()) return false;

        Set<UUID> fallidas = escribir(lote);
        var enviadas = lote.stream().map(InteraccionPendiente::eventId).filter(id -> !fallidas.contains(id)).toList();
        outbox.marcarSincronizadas(enviadas);
        for (InteraccionPendiente pendiente : lote) {
            if (fallidas.contains(pendiente.eventId())) {
                int intentos = pendiente.intentos() + 1;
                outbox.posponer(pendiente.eventId(), intentos, esperaSegundos(intentos));
            }
        }
        metrics.counter(METRICA, "result", "sent").increment(enviadas.size());
        metrics.counter(METRICA, "result", "retry").increment(fallidas.size());
        return fallidas.isEmpty() && lote.size() == DynamoInteraccionRepository.LOTE_MAXIMO;
    }

    private Set<UUID> escribir(List<InteraccionPendiente> lote) {
        try {
            return dynamo.guardar(lote);
        } catch (RuntimeException fallo) {
            log.warn("No se pudo escribir en DynamoDB un lote de {} interacciones: {}", lote.size(), fallo.getMessage());
            return Set.copyOf(lote.stream().map(InteraccionPendiente::eventId).toList());
        }
    }

    /** 5, 10, 20, 40, 80, 160 y luego 300 s. */
    static long esperaSegundos(int intentos) {
        return Math.min(300L, 5L << Math.min(intentos - 1, 6));
    }
}
