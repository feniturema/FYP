package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillOrderMessage;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T3 of the SecKill pipeline (docs/phases/P2.md §6.1, §6.7): one transaction that inserts the order,
 * bumps {@code sold_count} under the {@code sold_count < seckill_stock} guard, and settles payment.
 * A redelivered message violates {@code tracking_token} / {@code uk_orders_buyer_seckill}; the caller
 * tells a duplicate from a real conflict with {@link #existsByTrackingToken}.
 */
@Service
public class SeckillOrderWriter {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderWriter.class);

    /** FakeWallet always succeeds, so "orders == stock" acceptance stays deterministic (MOCK_FPX fails 10%). */
    public static final String SECKILL_PAYMENT = "FAKE_WALLET";

    private final OrderRepository orderRepository;
    private final SeckillEventRepository eventRepository;
    private final OrderService orderService;

    public SeckillOrderWriter(OrderRepository orderRepository, SeckillEventRepository eventRepository,
                              OrderService orderService) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.orderService = orderService;
    }

    @Transactional
    public Order persist(SeckillOrderMessage m) {
        Order order = orderRepository.saveAndFlush(Order.builder()
                .buyerId(m.userId())
                .sourceType(Order.SourceType.SECKILL)
                .refId(m.eventId())
                .seckillEventId(m.eventId())
                .amount(m.price())
                .paymentMethod(SECKILL_PAYMENT)
                .trackingToken(m.orderId())
                .status(Order.Status.PENDING)
                .build());
        if (eventRepository.incrementSold(m.eventId()) == 0) {
            throw new IllegalStateException("sold_count guard rejected order " + m.orderId()
                    + " for event " + m.eventId() + " (event missing or sold_count >= seckill_stock)");
        }
        orderService.settle(order, SECKILL_PAYMENT);
        log.info("[SecKill] persisted order {} (token={}) status={}", order.getId(), m.orderId(), order.getStatus());
        return order;
    }

    @Transactional(readOnly = true)
    public boolean existsByTrackingToken(String trackingToken) {
        return orderRepository.existsByTrackingToken(trackingToken);
    }

    /**
     * Like {@link #existsByTrackingToken} but never throws: {@code null} means the check itself failed
     * (e.g. the database is unreachable), so the caller cannot tell whether the order was committed.
     * Deliberately not {@code @Transactional}: a failure to open a transaction must also land here.
     * Used only by the SYNC benchmark mode (docs/phases/P3.md §6.1).
     */
    public Boolean existsByTrackingTokenSafely(String trackingToken) {
        try {
            return orderRepository.existsByTrackingToken(trackingToken);
        } catch (RuntimeException e) {
            log.warn("[SecKill] order existence check failed for token={}: {}", trackingToken, e.toString());
            return null;
        }
    }
}
