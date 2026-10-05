package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface SeckillEventRepository extends JpaRepository<SeckillEvent, Long> {
    List<SeckillEvent> findByStatus(SeckillEvent.Status status);

    List<SeckillEvent> findByProductIdAndStatus(Long productId, SeckillEvent.Status status);
    List<SeckillEvent> findByStockWarmedFalseAndStartTimeBefore(Instant time);
    List<SeckillEvent> findByStatusAndReconciledFalse(SeckillEvent.Status status);

    /** MySQL-side oversell guard: returns 0 when the event is already fully sold. */
    @Modifying(flushAutomatically = true)
    @Query("update SeckillEvent e set e.soldCount = e.soldCount + 1 where e.id = :id and e.soldCount < e.seckillStock")
    int incrementSold(Long id);

    /** Sets only the reconciled flag (never writes a possibly stale sold_count back). */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update SeckillEvent e set e.reconciled = true where e.id = :id")
    int markReconciled(Long id);
}
