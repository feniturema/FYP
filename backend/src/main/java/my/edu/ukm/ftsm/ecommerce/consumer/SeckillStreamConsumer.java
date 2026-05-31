package my.edu.ukm.ftsm.ecommerce.consumer;

import jakarta.annotation.PostConstruct;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.service.OrderService;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Drains the {@code seckill:orders} Redis Stream using a consumer group and
 * persists confirmed orders to MySQL. This decouples the high-concurrency
 * accept path from durable database writes.
 */
@Component
public class SeckillStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillStreamConsumer.class);
    private static final String CONSUMER_NAME = "consumer-1";
    private static final String SECKILL_PAYMENT = "FAKE_WALLET";

    private final StringRedisTemplate redis;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public SeckillStreamConsumer(StringRedisTemplate redis, OrderRepository orderRepository,
                                 OrderService orderService) {
        this.redis = redis;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @PostConstruct
    public void ensureGroup() {
        String stream = RedisKeys.seckillOrdersStream();
        String group = RedisKeys.seckillConsumerGroup();
        try {
            // MKSTREAM creates the stream if absent; start reading new messages ($).
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("[SecKill] created consumer group {} on {}", group, stream);
        } catch (DataAccessException ex) {
            // BUSYGROUP: group already exists — fine.
            log.debug("[SecKill] consumer group already exists: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = 500)
    public void drain() {
        String stream = RedisKeys.seckillOrdersStream();
        String group = RedisKeys.seckillConsumerGroup();
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(group, CONSUMER_NAME),
                    StreamReadOptions.empty().count(20),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    process(record.getValue());
                    redis.opsForStream().acknowledge(group, record);
                } catch (Exception e) {
                    log.error("[SecKill] failed to process record {}: {}", record.getId(), e.getMessage(), e);
                    // left unacked -> will appear in pending entries for later inspection
                }
            }
        } catch (Exception ex) {
            log.warn("[SecKill] stream read error: {}", ex.getMessage());
        }
    }

    private void process(Map<Object, Object> v) {
        String token = String.valueOf(v.get("token"));
        // idempotency: skip if this token was already persisted
        if (orderRepository.findByTrackingToken(token).isPresent()) {
            return;
        }
        Long userId = Long.valueOf(String.valueOf(v.get("userId")));
        Long eventId = Long.valueOf(String.valueOf(v.get("eventId")));
        BigDecimal price = new BigDecimal(String.valueOf(v.get("price")));

        Order order = Order.builder()
                .buyerId(userId)
                .sourceType(Order.SourceType.SECKILL)
                .refId(eventId)
                .amount(price)
                .paymentMethod(SECKILL_PAYMENT)
                .trackingToken(token)
                .status(Order.Status.PENDING)
                .build();
        order = orderRepository.save(order);

        // settle payment (mock) — flips to PAID/FAILED
        orderService.settle(order, SECKILL_PAYMENT);
        log.info("[SecKill] persisted order {} (token={}) status={}", order.getId(), token, order.getStatus());
    }
}
