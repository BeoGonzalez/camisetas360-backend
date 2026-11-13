package com.camisetas360.carrito.messaging;

import com.camisetas360.carrito.messaging.event.InteraccionEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica eventos de analitica en Kafka.
 *
 * - Dentro de una transaccion, el evento se envia solo despues del commit:
 *   nunca se anuncia un cambio de carrito que termino en rollback.
 * - Un Kafka caido no afecta la operacion del usuario: el error se registra.
 * - La clave de particion es el usuario, para conservar el orden de su navegacion.
 */
@Component
public class InteraccionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InteraccionEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper json = JsonMapper.builder().build();
    private final boolean enabled;

    public InteraccionEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     @Value("${app.analytics.events-enabled:true}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
    }

    public void publicar(String topic, InteraccionEvento evento) {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviar(topic, evento);
                }
            });
        } else {
            enviar(topic, evento);
        }
    }

    private void enviar(String topic, InteraccionEvento evento) {
        try {
            kafkaTemplate.send(topic, evento.usuarioId(), json.writeValueAsString(evento))
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.warn("No se pudo publicar evento de analitica eventId={} topic={}: {}",
                                    evento.eventId(), topic, failure.getMessage());
                        }
                    });
        } catch (RuntimeException failure) {
            log.warn("No se pudo publicar evento de analitica eventId={} topic={}: {}",
                    evento.eventId(), topic, failure.getMessage());
        }
    }
}
