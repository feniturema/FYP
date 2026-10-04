package my.edu.ukm.ftsm.ecommerce.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Unified order/transaction across all sale channels.
 */
@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(
        name = "uk_orders_buyer_seckill", columnNames = {"buyer_id", "seckill_event_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    public enum SourceType { C2C_ITEM, B2C_PRODUCT, SECKILL }

    public enum Status { PENDING, PAID, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buyerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    /** itemId / productId / seckillEventId depending on sourceType. */
    @Column(nullable = false)
    private Long refId;

    /** Set only for SECKILL orders (== refId); one order per buyer per event (uk_orders_buyer_seckill). */
    private Long seckillEventId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    private String paymentMethod;

    /** Idempotency / tracking token returned to the client (used by SecKill polling). */
    @Column(unique = true)
    private String trackingToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
