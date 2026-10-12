package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.exception.InvalidEventContractException;
import com.camisetas360.recommendation.parser.OrderEventParser;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.OrderInboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultOrderEventInboxServiceTest {

    private final OrderEventParser parser = mock(OrderEventParser.class);
    private final OrderInboxRepository repository = mock(OrderInboxRepository.class);
    private final DefaultOrderEventInboxService service = new DefaultOrderEventInboxService(parser, repository);

    @Test
    @DisplayName("Should parse and persist valid event, returning true for new event")
    void shouldParseAndPersistValidEvent() {
        UUID eventId = UUID.randomUUID();
        long orderId = 42L;
        byte[] body = "{\"eventId\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        ParsedOrderEvent parsed = new ParsedOrderEvent(eventId, orderId, 2);

        when(parser.parse(body)).thenReturn(parsed);
        when(repository.saveIfAbsent(eventId, orderId, new String(body, StandardCharsets.UTF_8))).thenReturn(true);

        boolean result = service.accept(body);

        assertThat(result).isTrue();
        verify(parser).parse(body);
        verify(repository).saveIfAbsent(eventId, orderId, new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should return false when repository reports duplicate event")
    void shouldReturnFalseForDuplicateEvent() {
        UUID eventId = UUID.randomUUID();
        long orderId = 42L;
        byte[] body = "{\"eventId\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        ParsedOrderEvent parsed = new ParsedOrderEvent(eventId, orderId, 1);

        when(parser.parse(body)).thenReturn(parsed);
        when(repository.saveIfAbsent(eventId, orderId, new String(body, StandardCharsets.UTF_8))).thenReturn(false);

        boolean result = service.accept(body);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should not call repository if parsing fails")
    void shouldNotCallRepositoryWhenParsingFails() {
        byte[] invalidBody = "invalid".getBytes(StandardCharsets.UTF_8);

        when(parser.parse(invalidBody)).thenThrow(new InvalidEventContractException("Invalid JSON"));

        assertThatThrownBy(() -> service.accept(invalidBody))
                .isInstanceOf(InvalidEventContractException.class);

        verifyNoInteractions(repository);
    }
}

