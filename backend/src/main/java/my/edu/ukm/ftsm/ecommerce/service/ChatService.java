package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ChatResponse;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI shopping assistant backed by a DeepSeek (OpenAI-compatible) endpoint.
 * Calls the LLM asynchronously via WebClient so latency never blocks Tomcat
 * worker threads handling marketplace/transaction traffic.
 * Builds a lightweight product-context prompt (keyword retrieval) before each call.
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

    private final WebClient llmWebClient;
    private final ProductRepository productRepository;
    private final String apiKey;
    private final String model;

    public ChatService(WebClient llmWebClient, ProductRepository productRepository,
                       @Value("${app.llm.api-key:}") String apiKey,
                       @Value("${app.llm.model:deepseek-chat}") String model) {
        this.llmWebClient = llmWebClient;
        this.productRepository = productRepository;
        this.apiKey = apiKey;
        this.model = model;
    }

    public Mono<ChatResponse> chat(String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just(new ChatResponse(
                    "AI assistant is not configured yet. " +
                    "Once configured, I can help you find products and answer questions!"));
        }

        String userContent = "=== Available products ===\n"
                + buildProductContext(userMessage)
                + "\n\n=== Student question ===\n"
                + userMessage;

        // OpenAI-compatible request body
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PERSONA),
                        Map.of("role", "user",   "content", userContent)
                ),
                "max_tokens", 512,
                "temperature", 0.7
        );

        return llmWebClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::extractText)
                .onErrorResume(err -> {
                    log.error("[Chat] LLM call failed: {}", err.getMessage());
                    return Mono.just(new ChatResponse(
                            "Sorry, I'm having trouble reaching the assistant right now. Please try again."));
                });
    }

    /** OpenAI-compatible response: choices[0].message.content */
    private ChatResponse extractText(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return new ChatResponse(content.isMissingNode() ? "(no response)" : content.asText());
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
