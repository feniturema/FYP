package my.edu.ukm.ftsm.ecommerce.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin, reusable wrapper around the OpenAI-compatible LLM endpoint (DeepSeek by default).
 * Shared by ChatService (agentic tool-calling) and SmartSearchService (intent reranking).
 *
 * <p>Calls block internally: the chat controller already blocks on the result, and a
 * synchronous loop keeps tool-calling logic simple while never running DB/Redis work on
 * Netty event-loop threads. Returns {@code null} on any failure so callers can degrade.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient llmWebClient;
    private final String apiKey;
    private final String model;

    public LlmClient(WebClient llmWebClient,
                     @Value("${app.llm.api-key:}") String apiKey,
                     @Value("${app.llm.model:deepseek-chat}") String model) {
        this.llmWebClient = llmWebClient;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /**
     * Blocking chat completion.
     *
     * @param messages OpenAI-style message list (system/user/assistant/tool roles)
     * @param tools    optional function-calling tool specs; pass null/empty to disable tools
     * @return raw response JSON, or {@code null} on error
     */
    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        // Reasoning models (deepseek-v4-*) spend tokens on reasoning before the visible
        // answer, so keep max_tokens generous or content comes back empty.
        body.put("max_tokens", 2048);
        body.put("temperature", 0.3);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        try {
            return llmWebClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.error("[LLM] chatCompletion failed: {}", e.getMessage());
            return null;
        }
    }
}
