package my.edu.ukm.ftsm.ecommerce.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.annotation.PreDestroy;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Short-TTL cache of SecKill event windows so the buy hot path does not query MySQL per request.
 * Unknown ids are cached as empty too (bounded by the TTL), so probing ids cannot hammer MySQL.
 * <p>
 * Loads never block inside the cache. A synchronous Caffeine loader runs inside
 * {@code ConcurrentHashMap.compute}'s monitor; on JDK 21 a virtual thread blocked there (on MySQL or on a Hikari
 * connection) pins its carrier, and every other buyer of the same event blocks on that monitor and pins one too,
 * until no virtual thread in the JVM can run (loadtest/results/H1-cache-fix/CACHE-FIX.md). Here the monitor only stores an
 * in-flight future, the query runs on {@link #loader}, and callers wait for the future outside any monitor.
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
    /**
     * Owned by this bean and shut down with it; never the common pool. One virtual thread per load, no queue, so
     * submitting from inside the cache never blocks or runs the load in the caller. There is no global limit:
     * concurrent gets of one key share a load, but each uncached key starts its own (as each request used to query
     * in its own thread), an invalidate during a load lets a second load of that key start, and maximumSize bounds
     * the stored entries, not the queries in flight. The query runs in its own read-only repository transaction,
     * never in the caller's.
     */
    private final ExecutorService loader;
    private final AsyncCache<Long, Optional<EventWindow>> cache;

    @Autowired
    public SeckillEventCache(SeckillEventRepository eventRepository,
                             @Value("${app.seckill.event-cache-ttl:5s}") Duration ttl) {
        this(eventRepository, ttl, Ticker.systemTicker());
    }

    SeckillEventCache(SeckillEventRepository eventRepository, Duration ttl, Ticker ticker) {
        this.eventRepository = eventRepository;
        this.loader = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("seckill-event-load-", 0).factory());
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).ticker(ticker).buildAsync();
    }

    public Optional<EventWindow> get(Long eventId) {
        CompletableFuture<Optional<EventWindow>> window =
                cache.get(eventId, (id, cacheExecutor) -> CompletableFuture.supplyAsync(() -> load(id), loader));
        try {
            return window.join();
        } catch (CompletionException e) {
            // A failed load is never cached: drop exactly this future now (Caffeine's own removal runs in a
            // completion callback that can lag behind join()), so the next get always retries.
            cache.asMap().remove(eventId, window);
            // Rethrow what the repository threw (e.g. a DataAccessException), as the synchronous loader did.
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
    }

    public void invalidate(Long eventId) {
        cache.synchronous().invalidate(eventId);
    }

    /** Stops the loader with the application context; in-flight loads get a short grace period. */
    @PreDestroy
    public void shutdown() {
        loader.shutdown();
        try {
            if (!loader.awaitTermination(5, TimeUnit.SECONDS)) {
                loader.shutdownNow();
            }
        } catch (InterruptedException e) {
            loader.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Optional<EventWindow> load(Long id) {
        return eventRepository.findById(id).map(EventWindow::from);
    }
}
