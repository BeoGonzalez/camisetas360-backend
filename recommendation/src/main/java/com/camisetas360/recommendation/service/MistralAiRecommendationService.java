package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Recommendation;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.RecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * Spring AI & Mistral AI implementation of {@link RecommendationService}.
 * Crafts specialized prompts based on the purchased items, invokes Mistral AI,
 * parses structured suggestions, and persists them via {@link RecommendationRepository}.
 * Implements graceful fallback when ChatModel is unavailable or during external API degradation.
 */
@Service
public class MistralAiRecommendationService implements RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(MistralAiRecommendationService.class);

    private static final String FALLBACK_ITEMS_JSON = """
            [
              {"name": "Camiseta Visitante 2026", "reason": "Complementa tu indumentaria con la versión alternativa oficial de la temporada."},
              {"name": "Camiseta Retro Edición Especial", "reason": "Diseño conmemorativo clásico imprescindible para fanáticos del buen fútbol."}
            ]
            """.trim();

    private static final String FALLBACK_EXPLANATION =
            "Recomendaciones de catálogo basadas en los productos más destacados de Camisetas360.";

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RecommendationRepository repository;
    private final JsonMapper jsonMapper;

    public MistralAiRecommendationService(
            ObjectProvider<ChatModel> chatModelProvider,
            RecommendationRepository repository,
            JsonMapper jsonMapper) {
        this.chatModelProvider = Objects.requireNonNull(chatModelProvider, "chatModelProvider cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
    }

    @Override
    public Recommendation generateAndSave(ParsedOrderEvent event, String rawPayload) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();

        if (chatModel != null) {
            try {
                String promptText = buildPrompt(event);
                log.info("Requesting AI recommendations from Mistral for order #{}", event.orderId());
                String responseText = chatModel.call(promptText);

                if (responseText != null && !responseText.isBlank()) {
                    return parseAndSave(event, responseText);
                }
            } catch (Exception e) {
                log.warn("Mistral AI call failed for order #{}: {}. Falling back to default recommendations.",
                        event.orderId(), e.getMessage());
            }
        } else {
            log.debug("ChatModel bean not active. Using rule-based catalog recommendations for order #{}",
                    event.orderId());
        }

        return repository.save(event.orderId(), event.userEmail(), FALLBACK_ITEMS_JSON, FALLBACK_EXPLANATION);
    }

    private String buildPrompt(ParsedOrderEvent event) {
        String purchasedItems = (event.items() != null && !event.items().isEmpty())
                ? String.join(", ", event.items())
                : "Camisetas oficiales de fútbol";

        String customer = event.userEmail() != null ? event.userEmail() : "cliente";

        return """
                Eres un asistente experto en fútbol y camisetas deportivas para la tienda Camisetas360.
                Un cliente (%s) ha comprado en la orden #%d los siguientes productos: %s.
                
                Recomienda 3 camisetas o accesorios complementarios para este cliente basados en su compra.
                Responde ÚNICAMENTE con un JSON válido con la siguiente estructura exacta:
                {
                  "recommendations": [
                    {
                      "name": "Nombre de la camiseta o accesorio recomendado",
                      "reason": "Explicación atractiva de por qué combina con su compra"
                    }
                  ],
                  "explanation": "Mensaje personalizado resumiendo el estilo del cliente"
                }
                No agregues texto introductorio ni conclusiones fuera del JSON.
                """.formatted(customer, event.orderId(), purchasedItems);
    }

    private Recommendation parseAndSave(ParsedOrderEvent event, String responseText) {
        String cleanJson = cleanMarkdownFences(responseText);
        try {
            JsonNode root = jsonMapper.readTree(cleanJson);
            String itemsJson = root.has("recommendations")
                    ? jsonMapper.writeValueAsString(root.get("recommendations"))
                    : cleanJson;

            String explanation = root.has("explanation")
                    ? root.path("explanation").asString()
                    : "Recomendaciones personalizadas generadas por IA para tu orden.";

            return repository.save(event.orderId(), event.userEmail(), itemsJson, explanation);
        } catch (Exception parseException) {
            log.warn("Failed to parse Mistral AI JSON response for order #{}: {}. Raw response: {}",
                    event.orderId(), parseException.getMessage(), cleanJson);
            return repository.save(event.orderId(), event.userEmail(), FALLBACK_ITEMS_JSON, FALLBACK_EXPLANATION);
        }
    }

    private String cleanMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}

