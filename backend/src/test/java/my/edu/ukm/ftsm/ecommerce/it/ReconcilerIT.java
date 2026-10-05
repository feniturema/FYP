package my.edu.ukm.ftsm.ecommerce.it;

import io.micrometer.core.instrument.MeterRegistry;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.service.SeckillReconciler;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Final verdict for an ended event with 5 orders, on a reconciler whose clock is fixed at endTime + 3 min (past the
 * 2 min settle delay). The clock constructor is package-private, so it is called reflectively with the context's own
 * repositories, Redis and meter registry (§6.1: only this IT needs the fixed clock).
 */
class ReconcilerIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 100_000_000L;

    @Autowired
    SeckillEventRepository eventRepository;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    OutboxDao outboxDao;
    @Autowired
    MeterRegistry meterRegistry;
    @Value("${app.seckill.reconcile-grace}")
    Duration grace;

    @Test
    void consistentEventIsReconciledWithoutMismatch() throws Exception {
        long eventId = endedEventWithFiveOrders(USER_BASE);
        double before = mismatch();

        reconcilerAt(endTime(eventId).plus(Duration.ofMinutes(3))).run();

        assertThat(reconciled(eventId)).isEqualTo(1);
        assertThat(mismatch() - before).isZero();
    }

    @Test
    void wrongRedisStockIsReportedAsMismatch() throws Exception {
        long eventId = endedEventWithFiveOrders(USER_BASE + 1_000);
        redis.opsForValue().set(RedisKeys.seckillStock(eventId), "3");   // 5 - 3 = 2 sold in Redis, 5 orders
        double before = mismatch();

        reconcilerAt(endTime(eventId).plus(Duration.ofMinutes(3))).run();

        assertThat(reconciled(eventId)).isEqualTo(1);
        assertThat(mismatch() - before).isEqualTo(1.0);
    }

    /** 5 orders through the real pipeline, outbox drained, then the window is moved into the past and ENDED. */
    private long endedEventWithFiveOrders(long userBase) {
        long eventId = createActiveEvent(createProduct(5, PRICE), 5);
        for (int i = 0; i < 5; i++) {
            assertThat(buy(tokenFor(userBase + i), eventId).statusCode()).isEqualTo(202);
        }
        awaitOrders(eventId, 5, Duration.ofSeconds(60));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxPending(eventId)).isZero());
        // Hibernate stores Instant columns in UTC; write the same way (a java.sql.Timestamp would use the JVM zone).
        Instant now = Instant.now();
        jdbc.update("UPDATE seckill_events SET start_time = ?, end_time = ? WHERE id = ?",
                LocalDateTime.ofInstant(now.minus(Duration.ofMinutes(10)), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.minusSeconds(1), ZoneOffset.UTC), eventId);
        seckillService.reconcileEvents();   // flips it to ENDED
        assertThat(jdbc.queryForObject("SELECT status FROM seckill_events WHERE id = ?", String.class, eventId))
                .isEqualTo("ENDED");
        return eventId;
    }

    private SeckillReconciler reconcilerAt(Instant instant) throws Exception {
        Constructor<SeckillReconciler> c = SeckillReconciler.class.getDeclaredConstructor(SeckillEventRepository.class,
                OrderRepository.class, OutboxDao.class, StringRedisTemplate.class, MeterRegistry.class,
                Duration.class, Clock.class);
        c.setAccessible(true);
        return c.newInstance(eventRepository, orderRepository, outboxDao, redis, meterRegistry, grace,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private Instant endTime(long eventId) {
        return eventRepository.findById(eventId).orElseThrow().getEndTime();   // read through the entity mapping
    }

    private int reconciled(long eventId) {
        return jdbc.queryForObject("SELECT CAST(reconciled AS UNSIGNED) FROM seckill_events WHERE id = ?",
                Integer.class, eventId);
    }

    private double mismatch() {
        return meterRegistry.counter("seckill.reconcile.mismatch").count();
    }
}
