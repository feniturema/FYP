package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.*;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.AssistantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Non-streaming chat endpoint, kept for existing clients: aggregates the same assistant stream as
 * {@code POST /api/assistant/stream} into one {@link ChatResponse} (docs/phases/P4b.md §4). Blocking here runs on a
 * virtual thread and keeps the servlet security context in place.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatbotController {

    private static final Logger log = LoggerFactory.getLogger(ChatbotController.class);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);
    static final String FAILED = "The assistant failed to answer. Please try again.";

    private final AssistantService assistantService;

    public ChatbotController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping
    public ChatResponse chat(@AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody ChatRequest req) {
        String reply = assistantService.stream(principal.userId(), req.conversationId(), req.message())
                .collectList()
                .map(tokens -> String.join("", tokens))
                .onErrorResume(e -> {
                    log.warn("[Assistant] chat failed for user {}: {}", principal.userId(), e.toString());
                    return Mono.just(FAILED);
                })
                .block(MAX_WAIT);
        return new ChatResponse(reply);
    }
}
