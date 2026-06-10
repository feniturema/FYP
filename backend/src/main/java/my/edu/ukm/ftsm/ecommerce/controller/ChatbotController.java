package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.*;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.ChatService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Chat endpoint for the agentic AI shopping assistant.
 * The user must be authenticated (handled by SecurityConfig); the principal's
 * userId is passed through so the assistant can look up the user's own orders.
 * ChatService runs the tool-calling loop synchronously (it blocks on LLM I/O
 * internally), keeping the servlet SecurityContext intact for tool DB access.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatbotController {

    private final ChatService chatService;

    public ChatbotController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@AuthenticationPrincipal AuthPrincipal principal,
                             @Valid @RequestBody ChatRequest req) {
        Long userId = principal != null ? principal.userId() : null;
        return chatService.chat(req.message(), userId);
    }
}
