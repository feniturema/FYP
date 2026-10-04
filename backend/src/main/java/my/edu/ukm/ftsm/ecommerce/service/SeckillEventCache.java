package my.edu.ukm.ftsm.ecommerce.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Short-TTL cache of SecKill event windows so the buy hot path does not query MySQL per request.
 * Unknown ids are cached as empty too (bounded by the TTL), so probing ids cannot hammer MySQL.
 */
@Component
public class SeckillEventCache {

    /** The immutable slice of an event the buy path needs. */
    public record EventWindow(Long eventId, Long productId, BigDecimal price, Instant startTime, Instant endTime) {

        static EventWindow from(SeckillEvent e) {
            return new EventWindow(e.getId(), e.getProductId(), e.getSeckillPrice(), e.getStartTime(), e.getEndTime());
        }

        /** Inclusive window, same rule as the pre-P2 check (not before start, not after end). */
        public boolean isActive(Instant now) {
            return !now.isBefore(startTime) && !now.isAfter(endTime);
        }
    }

    private final SeckillEventRepository eventRepository;
    private final Cache<Long, Optional<EventWindow>> cache;

    public SeckillEventCache(SeckillEventRepository eventRepository,
                             @Value("${app.seckill.event-cache-ttl:5s}") Duration ttl) {
        this.eventRepository = eventRepository;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    public Optional<EventWindow> get(Long eventId) {
        return cache.get(eventId, id -> eventRepository.findById(id).map(EventWindow::from));
    }

    public void invalidate(Long eventId) {
        cache.invalidate(eventId);
    }
}
