package com.camisetas360.pago.messaging;

import com.camisetas360.pago.model.OutboxEvento;
import com.camisetas360.pago.repository.OutboxEventoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventoRepository outboxEventoRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OutboxRelay(OutboxEventoRepository outboxEventoRepository,
                       KafkaTemplate<String, Object> kafkaTemplate) {
        this.outboxEventoRepository = outboxEventoRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.scheduler.delay-ms:2000}")
    @Transactional
    public void procesarEventosPendientes() {
        List<OutboxEvento> pendientes = outboxEventoRepository.findTop50ByEstadoOrderByCreatedAtAsc("PENDIENTE");

        if (pendientes.isEmpty()) {
            return;
        }

        log.debug("Procesando {} eventos pendientes de outbox...", pendientes.size());

        for (OutboxEvento evento : pendientes) {
            try {
                // Envío síncrono con timeout para garantizar entrega antes de marcar PUBLICADO
                kafkaTemplate.send(evento.getTopic(), evento.getPartitionKey(), evento.getPayload())
                        .get(5, TimeUnit.SECONDS);

                evento.setEstado("PUBLICADO");
                evento.setProcessedAt(Instant.now());
                log.info("Evento outbox publicado en Kafka: id={}, topic={}, key={}",
                        evento.getId(), evento.getTopic(), evento.getPartitionKey());
            } catch (Exception e) {
                log.error("Error al publicar evento outbox {} a Kafka: {}", evento.getId(), e.getMessage());
                evento.setIntentos(evento.getIntentos() + 1);
                evento.setUltimoError(e.getMessage());
                if (evento.getIntentos() >= 5) {
                    evento.setEstado("FALLIDO");
                }
            }
            outboxEventoRepository.save(evento);
        }
    }
}
