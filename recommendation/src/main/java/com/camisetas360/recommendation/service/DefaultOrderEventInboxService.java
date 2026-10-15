package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.messaging.OrderEventInbox;
import com.camisetas360.recommendation.parser.OrderEventParser;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.OrderInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Default implementation of {@link OrderEventInbox}.
 * Coordinates contract parsing, durable inbox persistence, and AI recommendation generation.
 */
@Service
public class DefaultOrderEventInboxService implements OrderEventInbox {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderEventInboxService.class);

    private final OrderEventParser parser;
    private final OrderInboxRepository repository;
    private final RecommendationService recommendationService;

    @Autowired
    public DefaultOrderEventInboxService(
            OrderEventParser parser,
            OrderInboxRepository repository,
            RecommendationService recommendationService) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.recommendationService = recommendationService;
    }

    public DefaultOrderEventInboxService(OrderEventParser parser, OrderInboxRepository repository) {
        this(parser, repository, null);
    }

    @Override
    @Transactional
    public boolean accept(byte[] body) {
        ParsedOrderEvent parsed = parser.parse(body);
        String rawPayload = new String(body, StandardCharsets.UTF_8);
        boolean saved = repository.saveIfAbsent(parsed.eventId(), parsed.orderId(), rawPayload);

        if (saved && recommendationService != null) {
            try {
                recommendationService.generateAndSave(parsed, rawPayload);
                repository.markProcessed(parsed.eventId());
                log.info("Recommendations generated and inbox marked processed for order #{}", parsed.orderId());
            } catch (Exception e) {
                log.warn("Recommendation generation failed for event {}: {}", parsed.eventId(), e.getMessage());
            }
        }

        return saved;
    }
}

