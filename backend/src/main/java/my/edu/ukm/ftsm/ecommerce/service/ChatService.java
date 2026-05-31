package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ChatResponse;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI shopping assistant. Calls Gemini asynchronously via WebClient so Gemini
 * latency never blocks Tomcat worker threads handling transactions. Builds a
 * lightweight product-context prompt (keyword retrieval) before each call.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PERSONA = """
            You are the FTSM Marketplace Assistant, a friendly helper for UKM students
            using a campus e-commerce platform. Help users find products, explain how
            buying/selling and flash-sale (SecKill) events work, and answer FAQs.
            Keep answers concise and student-friendly. If asked about products, use the
            provided product context. If you don't know, say so honestly.
            """;

    private final WebClient geminiWebClient;
    private final ProductRepository productRepository;
    private final String apiKey;
    private final String model;

    public ChatService(WebClient geminiWebClient, ProductRepository productRepository,
                       @Value("${app.gemini.api-key:}") String apiKey,
                       @Value("${app.gemini.model:gemini-1.5-flash}") String model) {
        this.geminiWebClient = geminiWebClient;
        this.productRepository = productRepository;
        this.apiKey = apiKey;
        this.model = model;
    }

    public Mono<ChatResponse> chat(String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just(new ChatResponse(
                    "AI assistant is not configured yet (missing GEMINI_API_KEY). " +
                    "Once configured, I can help you find products and answer questions!"));
        }

        String prompt = SYSTEM_PERSONA
                + "\n\n=== Product context ===\n" + buildProductContext(userMessage)
                + "\n\n=== User question ===\n" + userMessage;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

        return geminiWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .queryParam("key", apiKey)
                        .build(model))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::extractText)
                .onErrorResume(err -> {
                    log.error("[Chat] Gemini call failed: {}", err.getMessage());
                    return Mono.just(new ChatResponse(
                            "Sorry, I'm having trouble reaching the assistant right now. Please try again."));
                });
    }

    private ChatResponse extractText(JsonNode root) {
        JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        return new ChatResponse(text.isMissingNode() ? "(no response)" : text.asText());
    }

    /** Lightweight retrieval: match active products by keyword tokens from the user message. */
    private String buildProductContext(String message) {
        List<Product> all = productRepository.findAll();
        String lower = message.toLowerCase();
        List<Product> matched = all.stream()
                .filter(p -> lower.contains(p.getName().toLowerCase())
                        || (p.getCategory() != null && lower.contains(p.getCategory().toLowerCase()))
                        || all.size() <= 10) // small catalog: include everything
                .limit(10)
                .toList();
        if (matched.isEmpty()) {
            return "(no matching products)";
        }
        return matched.stream()
                .map(p -> "- " + p.getName() + " | RM" + p.getPrice() + " | stock " + p.getTotalStock()
                        + " | " + (p.getCategory() == null ? "uncategorised" : p.getCategory()))
                .collect(Collectors.joining("\n"));
    }
}
