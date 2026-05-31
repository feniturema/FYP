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

    /** Returned by the buy endpoint (202 Accepted). */
    public record SeckillBuyResponse(
            String result,        // ACCEPTED | SOLD_OUT | ALREADY_BOUGHT | NOT_ACTIVE
            String trackingToken, // present only on ACCEPTED
            String message
    ) {}

    /** Returned by the result-polling endpoint. */
    public record SeckillResultResponse(
            String trackingToken,
            String orderStatus,   // PENDING | PAID | FAILED | NOT_FOUND
            Long orderId
    ) {}
}
