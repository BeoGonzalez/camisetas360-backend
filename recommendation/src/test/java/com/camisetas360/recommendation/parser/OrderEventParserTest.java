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
        assertThat(event.lineas()).containsExactly(new ParsedOrderEvent.Linea("CAM-01", 2, 25.0));
        assertThat(event.usuarioId()).isNull();
        assertThat(event.occurredAt()).isNull();
    }

    @Test
    @DisplayName("Builds usuarioId <tid>:<oid> from the token owner and keeps occurredAt")
    void shouldBuildOwnerFromTenantAndObjectId() {
        String json = """
                {
                    "eventId": "%s",
                    "orderId": 7,
                    "occurredAt": "2026-01-01T12:00:00Z",
                    "tenantId": "11111111-1111-1111-1111-111111111111",
                    "objectId": "22222222-2222-2222-2222-222222222222",
                    "items": [{"sku": "CAM-01", "quantity": 1, "unitPrice": 25.0}]
                }
                """.formatted(UUID.randomUUID());

        ParsedOrderEvent event = parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(event.usuarioId())
                .isEqualTo("11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222");
        assertThat(event.occurredAt()).isEqualTo(java.time.Instant.parse("2026-01-01T12:00:00Z"));
    }

    @Test
    @DisplayName("Accepts occurredAt written as epoch seconds and ignores unparseable values")
    void shouldBeLenientWithOccurredAt() {
        String base = "{\"eventId\":\"%s\",\"orderId\":7,\"occurredAt\":%s,\"items\":[{\"sku\":\"A\"}]}";
        assertThat(parser.parse(base.formatted(UUID.randomUUID(), "1767268800.5").getBytes(StandardCharsets.UTF_8)).occurredAt())
                .isEqualTo(java.time.Instant.parse("2026-01-01T12:00:00.500Z"));
        assertThat(parser.parse(base.formatted(UUID.randomUUID(), "\"ayer\"").getBytes(StandardCharsets.UTF_8)).occurredAt())
                .isNull();
    }

    @Test
    @DisplayName("Should parse every order line")
    void shouldParseEveryLine() {
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

        assertThat(event.lineas()).extracting(ParsedOrderEvent.Linea::sku).containsExactly("CAM-01", "CAM-02");
        assertThat(event.lineas()).extracting(ParsedOrderEvent.Linea::quantity).containsExactly(2, 1);
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
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":42,\"items\":\"not-array\"}",
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":42,\"items\":[{\"quantity\":1}]}",
            "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":42,\"tenantId\":\"bad\",\"items\":[{\"sku\":\"A\"}]}"
    })
    @DisplayName("Should throw InvalidEventContractException for malformed payloads")
    void shouldThrowForMalformedPayloads(String invalidJson) {
        assertThatThrownBy(() -> parser.parse(invalidJson.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidEventContractException.class);
    }
}
