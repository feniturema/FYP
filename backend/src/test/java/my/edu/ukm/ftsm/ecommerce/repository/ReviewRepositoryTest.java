package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.Review;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:review-repo;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ReviewRepositoryTest {

    @Autowired
    private ReviewRepository reviewRepository;

    @Test
    void findsReviewsForTarget() {
        reviewRepository.save(Review.builder()
                .authorId(1L)
                .targetType(Review.TargetType.PRODUCT)
                .targetRefId(10L)
                .rating(5)
                .comment("Great")
                .build());

        assertThat(reviewRepository.findByTargetTypeAndTargetRefId(Review.TargetType.PRODUCT, 10L))
                .hasSize(1)
                .first()
                .extracting(Review::getRating)
                .isEqualTo(5);
    }
}
