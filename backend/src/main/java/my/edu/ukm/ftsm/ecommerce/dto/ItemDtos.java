package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import my.edu.ukm.ftsm.ecommerce.model.Item;

import java.math.BigDecimal;
import java.time.Instant;

public final class ItemDtos {

    private ItemDtos() {}

    public record ItemDraftResponse(
            String title,
            String description,
            String category,
            String condition,
            BigDecimal suggestedPrice
    ) {}

    public record CreateItemRequest(
            @NotBlank String title,
            String description,
            @NotNull @Positive BigDecimal price,
            String category,
            String condition,
            String imageUrl
    ) {}

    public record ItemResponse(
            Long id,
            Long sellerId,
            String title,
            String description,
            BigDecimal price,
            String category,
            String condition,
            String imageUrl,
            String status,
            Instant createdAt
    ) {
        public static ItemResponse from(Item i) {
            return new ItemResponse(i.getId(), i.getSellerId(), i.getTitle(), i.getDescription(),
                    i.getPrice(), i.getCategory(), i.getCondition(), i.getImageUrl(),
                    i.getStatus().name(), i.getCreatedAt());
        }
    }
}
