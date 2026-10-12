package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.parser.JsonOrderEventParser;
import com.camisetas360.recommendation.repository.JdbcOrderInboxRepository;
import com.camisetas360.recommendation.service.DefaultOrderEventInboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventInboxTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderEventInbox inbox = new DefaultOrderEventInboxService(
            new JsonOrderEventParser(JsonMapper.builder().build()),
            new JdbcOrderInboxRepository(jdbc)
    );

    @Test
    @DisplayName("Malformed events should be rejected before reaching the database")
    void malformedEventDoesNotReachDatabase() {
        for (String invalid : new String[]{
                "not-json",
                "{}",
                "{\"eventId\":\"bad\",\"orderId\":42}",
                "{\"eventId\":\"00000000-0000-0000-0000-000000000002\",\"orderId\":42,\"items\":[]}"}) {
            assertThatThrownBy(() -> inbox.accept(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }
}
