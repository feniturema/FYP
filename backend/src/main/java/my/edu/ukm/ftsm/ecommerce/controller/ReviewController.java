package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ReviewDtos.*;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.ReviewService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ReviewResponse create(@AuthenticationPrincipal AuthPrincipal principal,
                                 @Valid @RequestBody CreateReviewRequest req) {
        return reviewService.create(principal.userId(), req);
    }

    @GetMapping
    public ReviewSummaryResponse list(@RequestParam String targetType, @RequestParam Long targetRefId) {
        return reviewService.list(targetType, targetRefId);
    }
}
