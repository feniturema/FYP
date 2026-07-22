package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ActionCard;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ChatResponse;
import my.edu.ukm.ftsm.ecommerce.service.llm.ChatTools;
import my.edu.ukm.ftsm.ecommerce.service.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agentic AI shopping assistant. Drives an OpenAI-compatible tool-calling loop
 * (DeepSeek): the model can call backend tools (search products/listings, check
 * live flash-sale stock, look up the user's orders) before composing a reply.
 * Returns structured {@link ActionCard}s so the chat widget can offer one-click
 * add-to-cart / view actions.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_ROUNDS = 3;
    private static final int MAX_ACTIONS = 6;

    private static final String SYSTEM_PERSONA = """
            You are the FTSM Marketplace Assistant, a friendly helper for UKM students
            using a campus e-commerce platform (official store + second-hand listings + flash sales).

            You have tools to:
            - search_products: official store products
            - search_items: second-hand student listings
            - get_seckill_status: live flash-sale (SecKill) events and remaining stock
            - get_my_orders: the current user's recent orders

            Call tools whenever the user asks about products, prices, stock, flash sales,
            recommendations, or their own orders — do not guess catalog data. After getting
            tool results, reply concisely and student-friendly, in the user's language
            (always answer in the exact language the user wrote their message in).
            Mention prices in RM. If nothing matches, say so honestly.
            Format: short plain sentences or simple dash bullets. You may use **bold**
            for product names and prices. Never use markdown tables, headings, or code blocks.
            """;

    private final LlmClient llm;
    private final ChatTools tools;
    private final ObjectMapper mapper;

    public ChatService(LlmClient llm, ChatTools tools, ObjectMapper mapper) {
        this.llm = llm;
        this.tools = tools;
        this.mapper = mapper;
    }

    public ChatResponse chat(String userMessage, Long userId) {
        if (!llm.isConfigured()) {
            return new ChatResponse(
                    "AI assistant is not configured yet. " +
                    "Once configured, I can help you find products and answer questions!");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", SYSTEM_PERSONA));
        messages.add(msg("user", userMessage));
        List<ActionCard> actions = new ArrayList<>();

        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                JsonNode resp = llm.chatCompletion(messages, ChatTools.TOOL_SPECS);
                if (resp == null) {
                    return fallback();
                }
                JsonNode message = resp.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");

                if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                    // Echo the assistant message (with its tool_calls) before the tool results.
                    messages.add(assistantWithToolCalls(message, toolCalls));
                    for (JsonNode tc : toolCalls) {
                        String id = tc.path("id").asText();
                        String name = tc.path("function").path("name").asText();
                        String argsJson = tc.path("function").path("arguments").asText("{}");
                        String result = tools.execute(name, argsJson, userId, actions);
                        messages.add(toolResult(id, result));
                    }
                    continue;
                }

                // No tool calls -> final answer.
                return new ChatResponse(textOrDefault(message), dedupe(actions));
            }

            // Round budget exhausted: force a final answer without tools.
            JsonNode resp = llm.chatCompletion(messages, null);
            String text = resp == null ? null
                    : textOrDefault(resp.path("choices").path(0).path("message"));
            return new ChatResponse(text == null || text.isBlank()
                    ? "Here's what I found above — let me know if you'd like more detail!" : text,
                    dedupe(actions));
        } catch (Exception e) {
            log.error("[Chat] agent loop failed: {}", e.getMessage());
            return fallback();
        }
    }

    // ---------- message helpers ----------

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> assistantWithToolCalls(JsonNode message, JsonNode toolCalls) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "assistant");
        JsonNode content = message.path("content");
        m.put("content", content.isMissingNode() || content.isNull() ? "" : content.asText());
        m.put("tool_calls", mapper.convertValue(toolCalls, List.class));
        return m;
    }

    private Map<String, Object> toolResult(String toolCallId, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        return m;
    }

    private String textOrDefault(JsonNode message) {
        JsonNode content = message.path("content");
        return content.isMissingNode() || content.isNull() || content.asText().isBlank()
                ? "(no response)" : content.asText();
    }

    private List<ActionCard> dedupe(List<ActionCard> actions) {
        List<ActionCard> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ActionCard a : actions) {
            if (seen.add(a.sourceType() + ":" + a.refId()) && out.size() < MAX_ACTIONS) {
                out.add(a);
            }
        }
        return out;
    }

    private ChatResponse fallback() {
        return new ChatResponse(
                "Sorry, I'm having trouble reaching the assistant right now. Please try again.");
    }
}
