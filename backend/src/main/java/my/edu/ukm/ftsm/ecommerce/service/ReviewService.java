package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.ReviewDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.exception.ResourceNotFoundException;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.model.Review;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ReviewRepository;
import my.edu.ukm.ftsm.ecommerce.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    public ReviewService(ReviewRepository reviewRepository, ProductRepository productRepository,
                         ItemRepository itemRepository, UserRepository userRepository,
                         OrderRepository orderRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public ReviewResponse create(Long authorId, CreateReviewRequest req) {
        Review.TargetType targetType = parseTargetType(req.targetType());
        assertTargetExists(targetType, req.targetRefId());
        assertPaidOrder(authorId, targetType, req.targetRefId());

        Review review = Review.builder()
                .authorId(authorId)
                .targetType(targetType)
                .targetRefId(req.targetRefId())
                .rating(req.rating())
                .comment(req.comment())
                .build();
        return ReviewResponse.from(reviewRepository.save(review));
    }

    public ReviewSummaryResponse list(String targetTypeValue, Long targetRefId) {
        Review.TargetType targetType = parseTargetType(targetTypeValue);
        assertTargetExists(targetType, targetRefId);
        List<ReviewResponse> reviews = reviewRepository.findByTargetTypeAndTargetRefId(targetType, targetRefId)
                .stream().map(ReviewResponse::from).toList();
        double average = reviews.stream().mapToInt(ReviewResponse::rating).average().orElse(0.0);
        return new ReviewSummaryResponse(targetType.name(), targetRefId, average, reviews.size(), reviews);
    }

    private Review.TargetType parseTargetType(String value) {
        try {
            return Review.TargetType.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new BusinessException("Invalid targetType: " + value);
        }
    }

    private void assertTargetExists(Review.TargetType targetType, Long targetRefId) {
        switch (targetType) {
            case PRODUCT -> productRepository.findById(targetRefId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + targetRefId));
            case ITEM -> itemRepository.findById(targetRefId)
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + targetRefId));
            case SELLER -> userRepository.findById(targetRefId)
                    .orElseThrow(() -> new ResourceNotFoundException("Seller not found: " + targetRefId));
        }
    }

    private void assertPaidOrder(Long authorId, Review.TargetType targetType, Long targetRefId) {
        boolean allowed = switch (targetType) {
            case PRODUCT -> orderRepository.existsByBuyerIdAndSourceTypeAndRefIdAndStatus(
                    authorId, Order.SourceType.B2C_PRODUCT, targetRefId, Order.Status.PAID);
            case ITEM -> orderRepository.existsByBuyerIdAndSourceTypeAndRefIdAndStatus(
                    authorId, Order.SourceType.C2C_ITEM, targetRefId, Order.Status.PAID);
            case SELLER -> true;
        };
        if (!allowed) {
            throw new BusinessException("You can only review items or products after a paid order.");
        }
    }
}
