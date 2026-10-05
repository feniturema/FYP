package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByTargetTypeAndTargetRefId(Review.TargetType targetType, Long targetRefId);
}
