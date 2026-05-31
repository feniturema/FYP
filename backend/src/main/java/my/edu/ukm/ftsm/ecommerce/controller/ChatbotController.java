package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.*;
import my.edu.ukm.ftsm.ecommerce.service.ChatService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Fully reactive endpoint — returns a Mono so the Gemini round-trip does not
 * block Tomcat worker threads handling marketplace/transaction traffic.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatbotController {

    private final ChatService chatService;

    public ChatbotController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest req) {
        return chatService.chat(req.message());
    }
}
