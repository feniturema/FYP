package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.*;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;

import java.math.BigDecimal;
import java.time.Instant;

public final class SeckillDtos {

    private SeckillDtos() {}

    public record CreateSeckillRequest(
            @NotNull Long productId,
            @NotNull @Positive BigDecimal seckillPrice,
            @NotNull @Positive Integer seckillStock,
            @NotNull Instant startTime,
            @NotNull Instant endTime
    ) {}

    public record UpdateSeckillRequest(
            @NotNull @Positive BigDecimal seckillPrice,
            @NotNull @Positive Integer seckillStock,
            @NotNull Instant startTime,
            @NotNull Instant endTime
    ) {}

    public record SeckillEventResponse(
            Long id,
            Long productId,
            String productName,
            String imageUrl,
            BigDecimal originalPrice,
            BigDecimal seckillPrice,
            Integer seckillStock,
            Instant startTime,
            Instant endTime,
            String status
    ) {}

    /** Returned by the buy endpoint: 202 ACCEPTED, 409 SOLD_OUT/ALREADY_BOUGHT/NOT_ACTIVE, 503 UNAVAILABLE. */
    public record SeckillBuyResponse(
            String result,        // ACCEPTED | SOLD_OUT | ALREADY_BOUGHT | NOT_ACTIVE | UNAVAILABLE
            String trackingToken, // present only on ACCEPTED
            String message
    ) {}

    /**
     * Kafka message v1 on {@code seckill.orders} (key = eventId), stored as JSON in
     * {@code order_outbox.payload}. Fields may only be added, never renamed or removed.
     */
    public record SeckillOrderMessage(
            String orderId,        // UUID v4 string, == orders.tracking_token, NOT NULL
            Long userId,           // NOT NULL
            Long eventId,          // NOT NULL, Kafka key (String.valueOf)
            Long productId,        // NOT NULL
            BigDecimal price,      // NOT NULL, scale 2
            Instant acceptedAt     // NOT NULL
    ) {}

    /** Returned by the result-polling endpoint. */
    public record SeckillResultResponse(
            String trackingToken,
            String orderStatus,   // PENDING | PAID | FAILED | NOT_FOUND
            Long orderId
    ) {}
}
