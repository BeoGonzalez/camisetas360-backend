package com.camisetas360.recommendation.parser;

import com.camisetas360.recommendation.exception.InvalidEventContractException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Valida el contrato de order.created. El propietario se toma de {@code tenantId}/{@code objectId};
 * si faltan (payload legacy) el evento sigue siendo valido pero no genera interacciones.
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

        UUID eventId = uuid(root, "eventId");
        if (eventId == null) {
            throw new InvalidEventContractException("Missing or blank eventId");
        }

        long orderId = root.path("orderId").asLong(-1L);
        if (orderId <= 0) {
            throw new InvalidEventContractException("orderId must be a positive integer, found: " + orderId);
        }

        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new InvalidEventContractException("Missing or empty items array");
        }

        List<ParsedOrderEvent.Linea> lineas = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            String sku = itemNode.path("sku").asString("");
            if (sku.isBlank() || sku.length() > 64) {
                throw new InvalidEventContractException("Every item needs a sku of 1-64 characters");
            }
            Double unitPrice = itemNode.path("unitPrice").isNumber() ? itemNode.path("unitPrice").asDouble() : null;
            lineas.add(new ParsedOrderEvent.Linea(sku, itemNode.path("quantity").asInt(1), unitPrice));
        }

        UUID tenantId = uuid(root, "tenantId");
        UUID objectId = uuid(root, "objectId");
        String usuarioId = tenantId != null && objectId != null ? tenantId + ":" + objectId : null;

        return new ParsedOrderEvent(eventId, orderId, usuarioId, lineas, instant(root));
    }

    private static UUID uuid(JsonNode root, String field) {
        String value = root.path(field).asString(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventContractException("Invalid " + field + " UUID: " + e.getMessage(), e);
        }
    }

    /** occurredAt es informativo: si falta o no se entiende, quien consume usa la hora de recepcion. */
    private static Instant instant(JsonNode root) {
        JsonNode node = root.path("occurredAt");
        try {
            if (node.isNumber()) {
                // Jackson con WRITE_DATES_AS_TIMESTAMPS: segundos con decimales desde epoch.
                var seconds = node.decimalValue();
                return Instant.ofEpochSecond(seconds.longValue(),
                        seconds.remainder(java.math.BigDecimal.ONE).movePointRight(9).intValue());
            }
            String value = node.asString(null);
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }
}
