package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.edu.ukm.ftsm.ecommerce.model.Order;

import java.math.BigDecimal;
import java.time.Instant;

public final class OrderDtos {

    private OrderDtos() {}

    /** Create an order for a C2C item or B2C product (non-seckill path). */
    public record CreateOrderRequest(
            @NotNull String sourceType, // C2C_ITEM | B2C_PRODUCT
            @NotNull Long refId,
            @NotBlank String paymentMethod // FAKE_WALLET | MOCK_FPX
    ) {}

    public record PayRequest(
            @NotBlank String paymentMethod
    ) {}

    public record OrderResponse(
            Long id,
            Long buyerId,
            String sourceType,
            Long refId,
            BigDecimal amount,
            String paymentMethod,
            String status,
            Instant createdAt
    ) {
        public static OrderResponse from(Order o) {
            return new OrderResponse(o.getId(), o.getBuyerId(), o.getSourceType().name(), o.getRefId(),
                    o.getAmount(), o.getPaymentMethod(), o.getStatus().name(), o.getCreatedAt());
        }
    }
}
