package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import my.edu.ukm.ftsm.ecommerce.model.Review;

import java.time.Instant;
import java.util.List;

public final class ReviewDtos {

    private ReviewDtos() {}

    public record CreateReviewRequest(
            @NotBlank String targetType,
            @NotNull Long targetRefId,
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 1000) String comment
    ) {}

    public record ReviewResponse(
            Long id,
            Long authorId,
            String targetType,
            Long targetRefId,
            Integer rating,
            String comment,
            Instant createdAt
    ) {
        public static ReviewResponse from(Review r) {
            return new ReviewResponse(r.getId(), r.getAuthorId(), r.getTargetType().name(),
                    r.getTargetRefId(), r.getRating(), r.getComment(), r.getCreatedAt());
        }
    }

    public record ReviewSummaryResponse(
            String targetType,
            Long targetRefId,
            double averageRating,
            long count,
            List<ReviewResponse> reviews
    ) {}
}
