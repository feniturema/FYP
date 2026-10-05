package my.edu.ukm.ftsm.ecommerce.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Streaming shopping assistant (docs/phases/P4b.md §6.4): Spring AI ChatClient with a bounded per-conversation memory,
 * the MCP catalogue tools plus the local {@code my_orders} tool, a rate limiter and a circuit breaker. Without a
 * {@link ChatModel} (LLM_PROVIDER=none, or deepseek without a key) it answers {@link #NOT_CONFIGURED}.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    public static final String NOT_CONFIGURED = "AI assistant is not configured yet.";
    public static final String UNAVAILABLE_TEXT = "Sorry, I'm having trouble reaching the assistant right now. Please try again.";
    static final String BASE_SYSTEM = """
            You are the FTSM Marketplace Assistant for UKM students. Use tools for any product, stock, price,
            flash-sale or order question; answer only from tool results; if a tool returns nothing, say you could
            not find it. Never reveal other users' data. Prices are in RM. Be concise.""";
    static final String MCP_DOWN_NOTE = "\nCatalogue tools are temporarily unavailable: tell the user you cannot look up products right now.";
    static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);

    private final ChatClient chatClient;          // null when no ChatModel is configured
    private final McpToolsProvider mcp;
    private final ToolCallRecorder recorder;
    private final ToolCallback[] localTools;

    public AssistantService(ObjectProvider<ChatModel> chatModel, ChatMemory chatMemory, McpToolsProvider mcp,
                            ToolCallRecorder recorder, OrderTools orderTools) {
        ChatModel model = chatModel.getIfAvailable();
        this.chatClient = model == null ? null : ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();
        this.mcp = mcp;
        this.recorder = recorder;
        this.localTools = ToolCallbacks.from(orderTools);
    }

    @RateLimiter(name = "llm")
    @CircuitBreaker(name = "llm", fallbackMethod = "fallback")
    public Flux<String> stream(Long userId, String clientConversationId, String message) {
        if (chatClient == null) {
            return Flux.just(NOT_CONFIGURED);
        }
        String conv = userId + ":" + Objects.requireNonNullElse(clientConversationId, "default");
        ToolCallback[] tools = recorder.wrap(conv, concat(mcp.currentTools(), localTools));
        return chatClient.prompt()
                .system(BASE_SYSTEM + (mcp.available() ? "" : MCP_DOWN_NOTE))
                .user(message)
                .toolCallbacks(tools)
                .toolContext(Map.of(OrderTools.USER_ID, userId, "conversationId", conv))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conv))
                .stream().content()
                .timeout(STREAM_TIMEOUT);
    }

    Flux<String> fallback(Long userId, String clientConversationId, String message, Throwable t) {
        log.warn("[Assistant] fallback for user {}: {}", userId, t.toString());
        return Flux.just(UNAVAILABLE_TEXT);
    }

    private static ToolCallback[] concat(ToolCallback[] a, ToolCallback[] b) {
        ToolCallback[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
