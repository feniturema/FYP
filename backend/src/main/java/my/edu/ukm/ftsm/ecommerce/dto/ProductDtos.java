package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.*;
import my.edu.ukm.ftsm.ecommerce.model.Product;

import java.math.BigDecimal;
import java.time.Instant;

public final class ProductDtos {

    private ProductDtos() {}

    public record CreateProductRequest(
            @NotBlank String name,
            String description,
            @NotNull @Positive BigDecimal price,
            String imageUrl,
            @NotNull @PositiveOrZero Integer totalStock,
            String category
    ) {}

    public record ProductResponse(
            Long id,
            String name,
            String description,
            BigDecimal price,
            String imageUrl,
            Integer totalStock,
            String category,
            Instant createdAt
    ) {
        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getPrice(),
                    p.getImageUrl(), p.getTotalStock(), p.getCategory(), p.getCreatedAt());
        }
    }
}
