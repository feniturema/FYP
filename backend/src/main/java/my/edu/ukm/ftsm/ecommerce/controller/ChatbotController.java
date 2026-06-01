package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.*;
import my.edu.ukm.ftsm.ecommerce.service.ChatService;
import org.springframework.web.bind.annotation.*;

/**
 * Chat endpoint. ChatService uses WebClient internally for non-blocking LLM I/O;
 * we block() here so the Servlet security context (JwtAuthFilter) is fully respected.
 * Spring Security 6 reactive method-level security is incompatible with the servlet
 * SecurityContextHolder when a controller returns Mono<> without a reactive security context.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatbotController {

    private final ChatService chatService;

    public ChatbotController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        return chatService.chat(req.message()).block();
    }
}
