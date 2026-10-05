package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ChatRequest;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.AssistantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Streaming assistant (docs/phases/P4b.md §6.4, §6.9): {@code event: token} per text chunk, then {@code event: done};
 * {@code event: error} if the stream fails. Requires a JWT ({@code anyRequest().authenticated()}).
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@AuthenticationPrincipal AuthPrincipal principal,
                                                @Valid @RequestBody ChatRequest req) {
        return assistantService.stream(principal.userId(), req.conversationId(), req.message())
                .map(token -> ServerSentEvent.builder(token).event("token").build())
                .concatWith(Flux.just(ServerSentEvent.builder("").event("done").build()))
                .onErrorResume(e -> {
                    log.warn("[Assistant] stream failed for user {}: {}", principal.userId(), e.toString());
                    return Flux.just(ServerSentEvent.builder("The assistant failed to answer. Please try again.")
                            .event("error").build());
                });
    }
}
