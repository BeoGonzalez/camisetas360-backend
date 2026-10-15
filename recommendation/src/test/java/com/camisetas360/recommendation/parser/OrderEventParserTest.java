package com.camisetas360.recommendation.parser;

import com.camisetas360.recommendation.exception.InvalidEventContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEventParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OrderEventParser parser = new JsonOrderEventParser(jsonMapper);

    @Test
    @DisplayName("Should successfully parse a valid order.created payload")
    void shouldParseValidOrderCreatedPayload() {
        UUID expectedEventId = UUID.randomUUID();
        long expectedOrderId = 42L;
        String json = """
                {
                    "eventId": "%s",
                    "orderId": %d,
                    "items": [
                        {"sku": "CAM-01", "quantity": 2, "unitPrice": 25.0}
                    ]
                }
                """.formatted(expectedEventId, expectedOrderId);

        ParsedOrderEvent event = parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(event).isNotNull();
        assertThat(event.eventId()).isEqualTo(expectedEventId);
        assertThat(event.orderId()).isEqualTo(expectedOrderId);
        assertThat(event.itemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should parse userEmail and items array when present")
    void shouldParseEmailAndItemsList() {
        UUID expectedEventId = UUID.randomUUID();
        long expectedOrderId = 42L;
        String json = """
                {
                    "eventId": "%s",
                    "orderId": %d,
                    "userEmail": "fan@camisetas360.test",
                    "items": [
                        {"sku": "CAM-01", "quantity": 2, "price": 25.0},
                        {"sku": "CAM-02", "quantity": 1, "price": 30.0}
                    ]
                }
                """.formatted(expectedEventId, expectedOrderId);

        ParsedOrderEvent event = parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(event.userEmail()).isEqualTo("fan@camisetas360.test");
        assertThat(event.items()).containsExactly("CAM-01 (x2)", "CAM-02 (x1)");
    }

    @Test
    @DisplayName("Should throw when payload is null or empty")
    void shouldThrowWhenPayloadIsNullOrEmpty() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(InvalidEventContractException.class)
                .hasMessageContaining("Payload cannot be null or empty");

        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(InvalidEventContractException.class)
                .hasMessageContaining("Payload cannot be null or empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "{}",
            "{\"eventId\":\"bad\",\"orderId\":42}",
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":42,\"items\":[]}",
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":-1,\"items\":[{\"sku\":\"A\"}]}",
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":0,\"items\":[{\"sku\":\"A\"}]}",
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":42,\"items\":\"not-array\"}"
    })
    @DisplayName("Should throw InvalidEventContractException for malformed payloads")
    void shouldThrowForMalformedPayloads(String invalidJson) {
        assertThatThrownBy(() -> parser.parse(invalidJson.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidEventContractException.class);
    }
}
