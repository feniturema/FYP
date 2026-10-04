package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.exception.ResourceNotFoundException;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final SeckillEventRepository eventRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redis;
    private final RedisScript<Long> deductScript;
    private final RedisScript<Long> rollbackScript;
    private final SeckillEventCache eventCache;
    private final OutboxDao outboxDao;
    private final ObjectMapper objectMapper;
    private final Counter uncertainCounter;
    private final Counter compensationFailedCounter;

    public SeckillService(SeckillEventRepository eventRepository, ProductRepository productRepository,
                          OrderRepository orderRepository, StringRedisTemplate redis,
                          @Qualifier("seckillDeductScript") RedisScript<Long> deductScript,
                          @Qualifier("seckillRollbackScript") RedisScript<Long> rollbackScript,
                          SeckillEventCache eventCache, OutboxDao outboxDao, ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.redis = redis;
        this.deductScript = deductScript;
        this.rollbackScript = rollbackScript;
        this.eventCache = eventCache;
        this.outboxDao = outboxDao;
        this.objectMapper = objectMapper;
        this.uncertainCounter = meterRegistry.counter("seckill.outbox.uncertain");
        this.compensationFailedCounter = meterRegistry.counter("seckill.compensation.failed");
    }

    // ---------- Queries ----------

    public List<SeckillEventResponse> listEvents() {
        return eventRepository.findAll().stream().map(this::toResponse).toList();
    }

    public SeckillEventResponse getEvent(Long id) {
        return toResponse(findEvent(id));
    }

    private SeckillEventResponse toResponse(SeckillEvent e) {
        Product p = productRepository.findById(e.getProductId()).orElse(null);
        return new SeckillEventResponse(
                e.getId(), e.getProductId(),
                p != null ? p.getName() : "(deleted product)",
                p != null ? p.getImageUrl() : null,
                p != null ? p.getPrice() : null,
                e.getSeckillPrice(), e.getSeckillStock(),
                e.getStartTime(), e.getEndTime(), e.getStatus().name());
    }

    // ---------- Admin: create ----------

    @Transactional
    public SeckillEventResponse createEvent(CreateSeckillRequest req) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessException("endTime must be after startTime.");
        }
        Product product = productRepository.findById(req.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + req.productId()));
        if (req.seckillStock() > product.getTotalStock()) {
            throw new BusinessException("SecKill stock cannot exceed product total stock ("
                    + product.getTotalStock() + ").");
        }
        SeckillEvent e = SeckillEvent.builder()
                .productId(req.productId())
                .seckillPrice(req.seckillPrice())
                .seckillStock(req.seckillStock())
                .startTime(req.startTime())
                .endTime(req.endTime())
                .status(SeckillEvent.Status.PENDING)
                .stockWarmed(false)
                .build();
        return toResponse(eventRepository.save(e));
    }

    @Transactional
    public SeckillEventResponse updateEvent(Long id, UpdateSeckillRequest req) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessException("endTime must be after startTime.");
        }
        SeckillEvent e = findEvent(id);
        if (e.getStatus() != SeckillEvent.Status.PENDING) {
            throw new BusinessException("Only PENDING SecKill events can be edited.");
        }
        Product product = productRepository.findById(e.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + e.getProductId()));
        if (req.seckillStock() > product.getTotalStock()) {
            throw new BusinessException("SecKill stock cannot exceed product total stock ("
                    + product.getTotalStock() + ").");
        }
        e.setSeckillPrice(req.seckillPrice());
        e.setSeckillStock(req.seckillStock());
        e.setStartTime(req.startTime());
        e.setEndTime(req.endTime());
        e.setStockWarmed(false);
        // Drop the old warm-up so the next warm (SET NX) uses the new stock; PENDING => nobody bought yet.
        redis.delete(List.of(RedisKeys.seckillStock(id), RedisKeys.seckillBought(id)));
        SeckillEventResponse saved = toResponse(eventRepository.save(e));
        eventCache.invalidate(id);
        return saved;
    }

    @Transactional
    public void deleteEvent(Long id) {
        SeckillEvent e = findEvent(id);
        eventRepository.delete(e);
        redis.delete(List.of(RedisKeys.seckillStock(id), RedisKeys.seckillBought(id)));
        eventCache.invalidate(id);
    }

    // ---------- Stock warming + lifecycle ----------

    /**
     * Runs every 10s: warm Redis stock for events about to start, and flip
     * PENDING -> ACTIVE -> ENDED based on the time window.
     */
    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void reconcileEvents() {
        Instant now = Instant.now();
        for (SeckillEvent e : eventRepository.findAll()) {
            boolean changed = false;

            if (!e.isStockWarmed() && !now.isAfter(e.getEndTime())) {
                warmStock(e);
                e.setStockWarmed(true);
                changed = true;
            }
            SeckillEvent.Status desired = computeStatus(e, now);
            if (desired != e.getStatus()) {
                e.setStatus(desired);
                changed = true;
            }
            if (changed) {
                eventRepository.save(e);
            }
        }
    }

    private SeckillEvent.Status computeStatus(SeckillEvent e, Instant now) {
        if (now.isBefore(e.getStartTime())) return SeckillEvent.Status.PENDING;
        if (now.isAfter(e.getEndTime())) return SeckillEvent.Status.ENDED;
        return SeckillEvent.Status.ACTIVE;
    }

    /** SET NX: never overwrite a live counter (e.g. after a restart or a lost stock_warmed update). */
    private void warmStock(SeckillEvent e) {
        Boolean set = redis.opsForValue().setIfAbsent(RedisKeys.seckillStock(e.getId()),
                String.valueOf(e.getSeckillStock()));
        if (Boolean.TRUE.equals(set)) {
            log.info("[SecKill] warmed stock for event {} = {}", e.getId(), e.getSeckillStock());
        } else {
            log.info("[SecKill] stock key for event {} already present, left unchanged", e.getId());
        }
    }

    // ---------- Buy (hot path) ----------

    /**
     * Hot path (docs/phases/P2.md §6.1, §6.6): cached event window, atomic Redis Lua deduction (T0),
     * then an autocommit outbox INSERT (T1). 202 is returned only once the purchase intent is durable.
     */
    public SeckillBuyResponse buy(Long userId, Long eventId) {
        var w = eventCache.get(eventId).orElse(null);
        if (w == null || !w.isActive(Instant.now())) return notActive("SecKill is not currently active.");
        List<String> keys = List.of(RedisKeys.seckillStock(eventId), RedisKeys.seckillBought(eventId));
        long r = Optional.ofNullable(redis.execute(deductScript, keys, String.valueOf(userId))).orElse(-2L);
        if (r == 0) return new SeckillBuyResponse("SOLD_OUT", null, "Sorry, this item is sold out.");
        if (r == -1) return new SeckillBuyResponse("ALREADY_BOUGHT", null,
                "You have already secured one. Limit: 1 per user.");
        if (r != 1) return notActive("SecKill stock is not available yet.");
        String orderId = UUID.randomUUID().toString();
        String payload = json(new SeckillOrderMessage(orderId, userId, eventId, w.productId(), w.price(), Instant.now()));
        try {
            outboxDao.insert(orderId, userId, eventId, payload);
            return accepted(orderId);
        } catch (DataAccessException e) {
            Boolean exists = outboxDao.existsByOrderIdSafely(orderId);
            if (Boolean.TRUE.equals(exists)) return accepted(orderId);
            if (exists == null) {          // cannot tell whether T1 committed: never compensate
                uncertain(orderId, userId, eventId, e);
                return unavailable();
            }
            compensate(keys, userId, e);   // logs + counts on failure
            return unavailable();
        }
    }

    private static SeckillBuyResponse accepted(String orderId) {
        return new SeckillBuyResponse("ACCEPTED", orderId, "Your order is being processed.");
    }

    private static SeckillBuyResponse notActive(String message) {
        return new SeckillBuyResponse("NOT_ACTIVE", null, message);
    }

    private static SeckillBuyResponse unavailable() {
        return new SeckillBuyResponse("UNAVAILABLE", null,
                "We could not confirm your order. Please check My Orders before trying again.");
    }

    private void uncertain(String orderId, Long userId, Long eventId, DataAccessException cause) {
        uncertainCounter.increment();
        log.error("[SecKill] UNCERTAIN orderId={} userId={} eventId={}: outbox insert failed and could not be "
                + "verified; slot NOT compensated", orderId, userId, eventId, cause);
    }

    private void compensate(List<String> keys, Long userId, DataAccessException cause) {
        try {
            Long released = redis.execute(rollbackScript, keys, String.valueOf(userId));
            log.warn("[SecKill] outbox insert failed (not written); compensated user {} on {} (released={})",
                    userId, keys.get(0), released, cause);
        } catch (RuntimeException ex) {
            compensationFailedCounter.increment();
            log.error("[SecKill] COMPENSATION_FAILED user {} on {}: slot not returned (insert error: {})",
                    userId, keys.get(0), cause.toString(), ex);
        }
    }

    private String json(SeckillOrderMessage m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise SeckillOrderMessage " + m.orderId(), e);
        }
    }

    // ---------- Result polling ----------

    public SeckillResultResponse result(String token) {
        Order order = orderRepository.findByTrackingToken(token).orElse(null);
        if (order == null) {
            return new SeckillResultResponse(token, "PENDING", null);
        }
        return new SeckillResultResponse(token, order.getStatus().name(), order.getId());
    }

    private SeckillEvent findEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SecKill event not found: " + id));
    }
}
