package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SeckillEventRepository extends JpaRepository<SeckillEvent, Long> {
    List<SeckillEvent> findByStatus(SeckillEvent.Status status);
    List<SeckillEvent> findByStockWarmedFalseAndStartTimeBefore(Instant time);
}
