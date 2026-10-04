package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);
    Optional<Order> findByTrackingToken(String trackingToken);
    boolean existsByTrackingToken(String trackingToken);
    long countBySeckillEventId(Long seckillEventId);
    boolean existsByBuyerIdAndSourceTypeAndRefIdAndStatus(Long buyerId, Order.SourceType sourceType,
                                                          Long refId, Order.Status status);
}
