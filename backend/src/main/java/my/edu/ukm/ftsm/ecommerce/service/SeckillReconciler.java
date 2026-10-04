package my.edu.ukm.ftsm.ecommerce.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Gives every ENDED SecKill event a final verdict (docs/phases/P2.md §6.8). It compares the outbox
 * (published / pending), the orders table, {@code sold_count} and the Redis counter. "Reconciled"
 * means a verdict was reached, not that the numbers agree; nothing is repaired automatically.
 */
@Component
public class SeckillReconciler {

    private static final Logger log = LoggerFactory.getLogger(SeckillReconciler.class);
    static final Duration SETTLE_DELAY = Duration.ofMinutes(2);

    /** Outcome of one event check; REDIS_UNAVAILABLE ends the current round. */
    enum Outcome { SKIPPED, WAITING, CONSISTENT, MISMATCH, INCOMPLETE, REDIS_UNAVAILABLE }

    private final SeckillEventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OutboxDao outboxDao;
    private final StringRedisTemplate redis;
    private final Duration grace;
    private final Clock clock;
    private final Counter mismatchCounter;
    private final Counter incompleteCounter;

    @Autowired
    public SeckillReconciler(SeckillEventRepository eventRepository, OrderRepository orderRepository,
                             OutboxDao outboxDao, StringRedisTemplate redis, MeterRegistry meterRegistry,
                             @Value("${app.seckill.reconcile-grace:30m}") Duration grace) {
        this(eventRepository, orderRepository, outboxDao, redis, meterRegistry, grace, Clock.systemUTC());
    }

    SeckillReconciler(SeckillEventRepository eventRepository, OrderRepository orderRepository, OutboxDao outboxDao,
                      StringRedisTemplate redis, MeterRegistry meterRegistry, Duration grace, Clock clock) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.outboxDao = outboxDao;
        this.redis = redis;
        this.grace = grace;
        this.clock = clock;
        this.mismatchCounter = meterRegistry.counter("seckill.reconcile.mismatch");
        this.incompleteCounter = meterRegistry.counter("seckill.reconcile.incomplete");
    }

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        for (SeckillEvent e : eventRepository.findByStatusAndReconciledFalse(SeckillEvent.Status.ENDED)) {
            try {
                if (reconcile(e) == Outcome.REDIS_UNAVAILABLE) {
                    return;
                }
            } catch (RuntimeException ex) {
                log.warn("[SecKill] reconcile of event {} failed, will retry: {}", e.getId(), ex.toString());
            }
        }
    }

    Outcome reconcile(SeckillEvent e) {
        Instant now = clock.instant();
        Long id = e.getId();
        if (e.getEndTime().plus(SETTLE_DELAY).isAfter(now)) {
            return Outcome.SKIPPED;
        }
        long pending = outboxDao.countPendingForEvent(id);
        long published = outboxDao.countAllForEvent(id);
        long orderCount = orderRepository.countBySeckillEventId(id);
        // Re-read after the other counts: the listener may have committed since the event list was loaded.
        long soldCount = eventRepository.findById(id).map(SeckillEvent::getSoldCount).orElse(e.getSoldCount());

        Long redisSold;
        try {
            String remaining = redis.opsForValue().get(RedisKeys.seckillStock(id));
            redisSold = remaining == null ? null : e.getSeckillStock() - Long.parseLong(remaining);
        } catch (RuntimeException ex) {
            log.warn("[SecKill] reconcile event {}: redis unavailable, will retry ({})", id, ex.toString());
            return Outcome.REDIS_UNAVAILABLE;
        }

        String numbers = String.format("pending=%d published=%d orders=%d sold_count=%d redisSold=%s",
                pending, published, orderCount, soldCount, redisSold);
        boolean caughtUp = pending == 0 && orderCount == published;
        if (!caughtUp) {
            if (now.isBefore(e.getEndTime().plus(grace))) {
                log.debug("[SecKill] reconcile event {}: consumer still catching up ({})", id, numbers);
                return Outcome.WAITING;
            }
            markReconciled(e);
            incompleteCounter.increment();
            log.warn("[SecKill] reconcile event {}: consumer backlog not drained within grace {}{} ({})",
                    id, grace, redisSold == null ? "; redis key missing" : "", numbers);
            return Outcome.INCOMPLETE;
        }
        if (redisSold == null) {
            markReconciled(e);
            incompleteCounter.increment();
            log.warn("[SecKill] reconcile event {}: redis key missing ({})", id, numbers);
            return Outcome.INCOMPLETE;
        }
        markReconciled(e);
        if (soldCount == orderCount && redisSold == orderCount) {
            log.info("[SecKill] reconcile event {}: consistent ({})", id, numbers);
            return Outcome.CONSISTENT;
        }
        mismatchCounter.increment();
        log.warn("[SecKill] reconcile event {}: MISMATCH{} ({})", id,
                redisSold > orderCount ? " - lost slots (undersell)" : "", numbers);
        return Outcome.MISMATCH;
    }

    /** Targeted UPDATE: the event here is detached, and save() would merge its stale sold_count back. */
    private void markReconciled(SeckillEvent e) {
        eventRepository.markReconciled(e.getId());
        e.setReconciled(true);
    }
}
