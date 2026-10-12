package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.messaging.OrderEventInbox;
import com.camisetas360.recommendation.parser.OrderEventParser;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.OrderInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Default implementation of {@link OrderEventInbox}.
 * Coordinates contract parsing and durable persistence within transactional boundaries.
 */
@Service
public class DefaultOrderEventInboxService implements OrderEventInbox {

    private final OrderEventParser parser;
    private final OrderInboxRepository repository;

    public DefaultOrderEventInboxService(OrderEventParser parser, OrderInboxRepository repository) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }

    @Override
    @Transactional
    public boolean accept(byte[] body) {
        ParsedOrderEvent parsed = parser.parse(body);
        String rawPayload = new String(body, StandardCharsets.UTF_8);
        return repository.saveIfAbsent(parsed.eventId(), parsed.orderId(), rawPayload);
    }
}

