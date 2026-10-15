package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Recommendation;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.RecommendationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MistralAiLiveIT {

    @Test
    @DisplayName("Should successfully connect to Mistral AI API and generate real recommendations")
    @EnabledIfEnvironmentVariable(named = "SPRING_AI_MISTRALAI_API_KEY", matches = ".+")
    void shouldCallRealMistralAiAndProduceRecommendations() {
        String apiKey = System.getenv("SPRING_AI_MISTRALAI_API_KEY");

        MistralAiApi api = MistralAiApi.builder().apiKey(apiKey).build();
        ChatModel chatModel = MistralAiChatModel.builder().mistralAiApi(api).build();

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatModel", chatModel);

        RecommendationRepository repository = mock(RecommendationRepository.class);
        when(repository.save(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new Recommendation(
                        1L, inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3), Instant.now()
                ));

        JsonMapper jsonMapper = JsonMapper.builder().build();
        MistralAiRecommendationService service = new MistralAiRecommendationService(
                beanFactory.getBeanProvider(ChatModel.class),
                repository,
                jsonMapper
        );

        ParsedOrderEvent event = new ParsedOrderEvent(
                UUID.randomUUID(), 42L, 2, "hincha@camisetas360.cl",
                List.of("Camiseta Local 2026 (x1)", "Gorra Oficial (x1)")
        );

        Recommendation recommendation = service.generateAndSave(event, "{}");

        assertThat(recommendation).isNotNull();
        assertThat(recommendation.orderId()).isEqualTo(42L);
        assertThat(recommendation.recommendedItems()).isNotBlank();
        assertThat(recommendation.recommendedItems()).contains("name");
        assertThat(recommendation.explanation()).isNotBlank();
        System.out.println("=== LIVE MISTRAL AI RECOMMENDATIONS ===");
        System.out.println(recommendation.recommendedItems());
        System.out.println("Explanation: " + recommendation.explanation());
    }
}

