package com.camisetas360.recommendation.parser;

import com.camisetas360.recommendation.exception.InvalidEventContractException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Jackson-based implementation of {@link OrderEventParser}.
 * Adheres to DIP by receiving the configured {@link JsonMapper} via constructor injection.
 */
@Component
public class JsonOrderEventParser implements OrderEventParser {

    private final JsonMapper jsonMapper;

    public JsonOrderEventParser(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
    }

    @Override
    public ParsedOrderEvent parse(byte[] body) throws InvalidEventContractException {
        if (body == null || body.length == 0) {
            throw new InvalidEventContractException("Payload cannot be null or empty");
        }

        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (Exception e) {
            throw new InvalidEventContractException("Malformed JSON payload: " + e.getMessage(), e);
        }

        if (root == null || !root.isObject()) {
            throw new InvalidEventContractException("JSON root must be an object");
        }

        UUID eventId;
        try {
            String eventIdStr = root.path("eventId").asString();
            if (eventIdStr == null || eventIdStr.isBlank()) {
                throw new InvalidEventContractException("Missing or blank eventId");
            }
            eventId = UUID.fromString(eventIdStr);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventContractException("Invalid eventId UUID: " + e.getMessage(), e);
        }

        long orderId = root.path("orderId").asLong(-1L);
        if (orderId <= 0) {
            throw new InvalidEventContractException("orderId must be a positive integer, found: " + orderId);
        }

        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new InvalidEventContractException("Missing or empty items array");
        }

        String userEmail = root.path("userEmail").asString(null);

        List<String> items = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            String sku = itemNode.path("sku").asString("");
            int quantity = itemNode.path("quantity").asInt(1);
            if (!sku.isBlank()) {
                items.add(sku + " (x" + quantity + ")");
            }
        }

        return new ParsedOrderEvent(eventId, orderId, itemsNode.size(), userEmail, items);
    }
}
