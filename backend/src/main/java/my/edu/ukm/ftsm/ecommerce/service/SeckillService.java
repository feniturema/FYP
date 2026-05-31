package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.exception.ResourceNotFoundException;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final SeckillEventRepository eventRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redis;
    private final RedisScript<Long> seckillDeductScript;

    public SeckillService(SeckillEventRepository eventRepository, ProductRepository productRepository,
                          OrderRepository orderRepository, StringRedisTemplate redis,
                          RedisScript<Long> seckillDeductScript) {
        this.eventRepository = eventRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.redis = redis;
        this.seckillDeductScript = seckillDeductScript;
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
        return toResponse(eventRepository.save(e));
    }

    @Transactional
    public void deleteEvent(Long id) {
        SeckillEvent e = findEvent(id);
        eventRepository.delete(e);
        redis.delete(List.of(RedisKeys.seckillStock(id), RedisKeys.seckillBought(id)));
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

    private void warmStock(SeckillEvent e) {
        redis.opsForValue().set(RedisKeys.seckillStock(e.getId()), String.valueOf(e.getSeckillStock()));
        log.info("[SecKill] warmed stock for event {} = {}", e.getId(), e.getSeckillStock());
    }

    // ---------- Buy (hot path) ----------

    /**
     * Hot path: NEVER touches MySQL inventory. Runs the atomic Lua script against Redis,
     * and on success enqueues an order onto a Redis Stream for async persistence.
     */
    public SeckillBuyResponse buy(Long userId, Long eventId) {
        SeckillEvent event = findEvent(eventId);
        Instant now = Instant.now();
        if (now.isBefore(event.getStartTime()) || now.isAfter(event.getEndTime())) {
            return new SeckillBuyResponse("NOT_ACTIVE", null, "SecKill is not currently active.");
        }

        List<String> keys = List.of(RedisKeys.seckillStock(eventId), RedisKeys.seckillBought(eventId));
        Long result = redis.execute(seckillDeductScript, keys, String.valueOf(userId));
        long r = result == null ? -2 : result;

        if (r == 1) {
            String token = UUID.randomUUID().toString();
            enqueueOrder(eventId, userId, event.getProductId(), event.getSeckillPrice().toPlainString(), token);
            return new SeckillBuyResponse("ACCEPTED", token, "Your order is being processed.");
        } else if (r == 0) {
            return new SeckillBuyResponse("SOLD_OUT", null, "Sorry, this item is sold out.");
        } else if (r == -1) {
            return new SeckillBuyResponse("ALREADY_BOUGHT", null, "You have already secured one. Limit: 1 per user.");
        } else {
            return new SeckillBuyResponse("NOT_ACTIVE", null, "SecKill stock is not available yet.");
        }
    }

    private void enqueueOrder(Long eventId, Long userId, Long productId, String price, String token) {
        Map<String, String> payload = new HashMap<>();
        payload.put("eventId", String.valueOf(eventId));
        payload.put("userId", String.valueOf(userId));
        payload.put("productId", String.valueOf(productId));
        payload.put("price", price);
        payload.put("token", token);
        MapRecord<String, String, String> record =
                StreamRecords.mapBacked(payload).withStreamKey(RedisKeys.seckillOrdersStream());
        redis.opsForStream().add(record);
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
