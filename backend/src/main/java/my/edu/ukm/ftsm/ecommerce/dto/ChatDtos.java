package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ChatDtos {

    private ChatDtos() {}

    /**
     * {@code conversationId} is optional; the server always prefixes it with the caller's user id, so a client
     * cannot read or write another user's conversation (docs/phases/P4b.md §6.4).
     */
    public record ChatRequest(
            @NotBlank @Size(max = 2000) String message,
            @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String conversationId
    ) {}

    public record ChatResponse(
            String reply
    ) {}
}
