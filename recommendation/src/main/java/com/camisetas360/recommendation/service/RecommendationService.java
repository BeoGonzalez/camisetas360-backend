package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Recommendation;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;

/**
 * Service abstraction for generating and persisting recommendations for orders.
 */
public interface RecommendationService {

    /**
     * Generates and persists recommendations for the given order event.
     *
     * @param event parsed order event
     * @param rawPayload raw JSON payload received from the queue
     * @return persisted {@link Recommendation}
     */
    Recommendation generateAndSave(ParsedOrderEvent event, String rawPayload);
}

