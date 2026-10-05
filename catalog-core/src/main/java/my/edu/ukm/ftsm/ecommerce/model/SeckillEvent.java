package my.edu.ukm.ftsm.ecommerce.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A flash-sale event bound to a B2C Product. Stock is mirrored into Redis
 * for the high-concurrency deduction path; this row is the source of truth
 * for configuration and reconciliation.
 */
@Entity
@Table(name = "seckill_events")
// Only changed columns are written: lifecycle/reconcile saves must never overwrite sold_count,
// which SeckillEventRepository.incrementSold updates concurrently with a conditional UPDATE.
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeckillEvent {

    public enum Status { PENDING, ACTIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal seckillPrice;

    @Column(nullable = false)
    private Integer seckillStock;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    /** True once stock has been warmed into Redis. */
    @Column(nullable = false)
    @Builder.Default
    private boolean stockWarmed = false;

    /** Orders persisted for this event; guarded by sold_count < seckill_stock (second line of defence). */
    @Column(nullable = false)
    @Builder.Default
    private Integer soldCount = 0;

    /** True once the reconciler has given its final verdict (not necessarily "consistent"). */
    @Column(nullable = false)
    @Builder.Default
    private boolean reconciled = false;
}
