package com.camisetas360.pago.service;

import com.camisetas360.pago.messaging.PagoEvento;
import com.camisetas360.pago.model.OutboxEvento;
import com.camisetas360.pago.repository.OutboxEventoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxEventoRepository outboxEventoRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventoRepository outboxEventoRepository, ObjectMapper objectMapper) {
        this.outboxEventoRepository = outboxEventoRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarEvento(String topic, String partitionKey, PagoEvento evento) {
        try {
            String payload = objectMapper.writeValueAsString(evento);
            OutboxEvento outbox = new OutboxEvento(
                    "PAGO",
                    evento.pagoId() != null ? evento.pagoId().toString() : evento.ordenId().toString(),
                    evento.eventType(),
                    topic,
                    partitionKey,
                    payload
            );
            outboxEventoRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error al serializar evento outbox", e);
        }
    }
}
