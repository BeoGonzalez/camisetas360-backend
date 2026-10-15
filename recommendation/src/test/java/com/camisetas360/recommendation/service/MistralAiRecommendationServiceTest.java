package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Recommendation;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.RecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MistralAiRecommendationServiceTest {

        @SuppressWarnings("unchecked")
        private final ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        private final ChatModel chatModel = mock(ChatModel.class);
        private final RecommendationRepository repository = mock(RecommendationRepository.class);
        private final JsonMapper jsonMapper = JsonMapper.builder().build();

        private MistralAiRecommendationService service;

        @BeforeEach
        void setUp() {
                service = new MistralAiRecommendationService(chatModelProvider, repository, jsonMapper);
        }

        @Test
        @DisplayName("Should invoke ChatModel and save parsed recommendations when ChatModel is available")
        void shouldInvokeChatModelAndSaveRecommendations() {
                when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

                String mistralResponse = """
                                {
                                  "recommendations": [
                                    {"name": "Camiseta Titular 2026", "reason": "La camiseta principal del club."},
                                    {"name": "Bufanda Oficial", "reason": "Accesorio perfecto para los partidos."}
                                  ],
                                  "explanation": "Combinaciones ideales para tu kit de aficionado."
                                }
                                """;

                when(chatModel.call(anyString())).thenReturn(mistralResponse);

                Recommendation expected = new Recommendation(
                                1L, 42L, "fan@camisetas360.test",
                                "[{\"name\":\"Camiseta Titular 2026\",\"reason\":\"La camiseta principal del club.\"},{\"name\":\"Bufanda Oficial\",\"reason\":\"Accesorio perfecto para los partidos.\"}]",
                                "Combinaciones ideales para tu kit de aficionado.",
                                Instant.now());

                when(repository.save(eq(42L), eq("fan@camisetas360.test"), anyString(),
                                eq("Combinaciones ideales para tu kit de aficionado.")))
                                .thenReturn(expected);

                ParsedOrderEvent event = new ParsedOrderEvent(
                                UUID.randomUUID(), 42L, 1, "fan@camisetas360.test", List.of("CAM-01 (x1)"));

                Recommendation result = service.generateAndSave(event, "{}");

                assertThat(result).isNotNull();
                assertThat(result.orderId()).isEqualTo(42L);
                assertThat(result.explanation()).isEqualTo("Combinaciones ideales para tu kit de aficionado.");
                verify(chatModel).call(contains("CAM-01 (x1)"));
        }

        @Test
        @DisplayName("Should clean markdown fences from LLM response before saving")
        void shouldCleanMarkdownFences() {
                when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

                String mistralResponse = """
                                ```json
                                {
                                  "recommendations": [
                                    {"name": "Camiseta Retro", "reason": "Gran estilo clásico."}
                                  ],
                                  "explanation": "Estilo vintage."
                                }
                                ```
                                """;

                when(chatModel.call(anyString())).thenReturn(mistralResponse);

                Recommendation expected = new Recommendation(
                                2L, 99L, "fan@camisetas360.test",
                                "[{\"name\":\"Camiseta Retro\",\"reason\":\"Gran estilo clásico.\"}]",
                                "Estilo vintage.",
                                Instant.now());

                when(repository.save(eq(99L), eq("fan@camisetas360.test"), anyString(), eq("Estilo vintage.")))
                                .thenReturn(expected);

                ParsedOrderEvent event = new ParsedOrderEvent(
                                UUID.randomUUID(), 99L, 1, "fan@camisetas360.test", List.of("CAM-03 (x1)"));

                Recommendation result = service.generateAndSave(event, "{}");

                assertThat(result).isNotNull();
                assertThat(result.explanation()).isEqualTo("Estilo vintage.");
        }

        @Test
        @DisplayName("Should use fallback recommendations when ChatModel is unavailable")
        void shouldUseFallbackWhenChatModelUnavailable() {
                when(chatModelProvider.getIfAvailable()).thenReturn(null);

                Recommendation fallback = new Recommendation(
                                3L, 100L, "guest@camisetas360.test", "[]", "Fallback", Instant.now());

                when(repository.save(eq(100L), eq("guest@camisetas360.test"), anyString(), anyString()))
                                .thenReturn(fallback);

                ParsedOrderEvent event = new ParsedOrderEvent(
                                UUID.randomUUID(), 100L, 1, "guest@camisetas360.test", List.of("CAM-01"));

                Recommendation result = service.generateAndSave(event, "{}");

                assertThat(result).isNotNull();
                verify(repository).save(eq(100L), eq("guest@camisetas360.test"), anyString(), anyString());
        }

        @Test
        @DisplayName("Should gracefully handle ChatModel exceptions and fallback without throwing")
        void shouldHandleChatModelExceptionWithFallback() {
                when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
                when(chatModel.call(anyString())).thenThrow(new RuntimeException("Mistral API rate limit reached"));

                Recommendation fallback = new Recommendation(
                                4L, 101L, "user@camisetas360.test", "[]", "Fallback", Instant.now());

                when(repository.save(eq(101L), eq("user@camisetas360.test"), anyString(), anyString()))
                                .thenReturn(fallback);

                ParsedOrderEvent event = new ParsedOrderEvent(
                                UUID.randomUUID(), 101L, 1, "user@camisetas360.test", List.of("CAM-02"));

                Recommendation result = service.generateAndSave(event, "{}");

                assertThat(result).isNotNull();
                verify(repository).save(eq(101L), eq("user@camisetas360.test"), anyString(), anyString());
        }
}
