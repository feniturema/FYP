package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {}

    public record ChatRequest(
            @NotBlank String message
    ) {}

    public record ChatResponse(
            String reply,
            List<ActionCard> actions
    ) {
        /** Convenience for text-only replies (no actionable product cards). */
        public ChatResponse(String reply) {
            this(reply, List.of());
        }
    }

    /**
     * A clickable suggestion the assistant surfaces in the chat widget.
     * The frontend renders "Add to cart" / "View" using these fields
     * (sourceType + refId align with the cart store's CartLine).
     */
    public record ActionCard(
            String sourceType,   // C2C_ITEM | B2C_PRODUCT
            Long refId,
            String name,
            BigDecimal price,
            String imageUrl
    ) {}
}
