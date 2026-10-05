package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillEventCacheTest {

    private static final Instant START = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-04T10:10:00Z");

    private SeckillEventRepository repo;
    private SeckillEventCache cache;

    @BeforeEach
    void setUp() {
        repo = mock(SeckillEventRepository.class);
        cache = new SeckillEventCache(repo, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    void hitDoesNotQueryTheRepositoryAgain() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L)));

        assertThat(cache.get(1L)).get().extracting(SeckillEventCache.EventWindow::productId).isEqualTo(10L);
        assertThat(cache.get(1L)).isPresent();

        verify(repo, times(1)).findById(1L);
    }

    @Test
    void invalidateForcesAReload() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L)));

        cache.get(1L);
        cache.invalidate(1L);
        cache.get(1L);

        verify(repo, times(2)).findById(1L);
    }

    @Test
    void unknownIdIsCachedAsEmpty() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThat(cache.get(99L)).isEmpty();
        assertThat(cache.get(99L)).isEmpty();

        verify(repo, times(1)).findById(99L);
    }

    @Test
    void windowIsInclusiveAtBothEnds() {
        var w = new SeckillEventCache.EventWindow(1L, 10L, BigDecimal.ONE, START, END);
        assertThat(w.isActive(START)).isTrue();
        assertThat(w.isActive(END)).isTrue();
        assertThat(w.isActive(START.minusMillis(1))).isFalse();
        assertThat(w.isActive(END.plusMillis(1))).isFalse();
    }

    @Test
    void loadFailureKeepsItsExceptionTypeAndIsRetried() {
        when(repo.findById(1L))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"))
                .thenReturn(Optional.of(event(1L)));

        assertThatThrownBy(() -> cache.get(1L))
                .isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("pool exhausted");
        assertThat(cache.get(1L)).isPresent();

        verify(repo, times(2)).findById(1L);
    }

    @Test
    void concurrentGetsOfOneKeyShareASingleLoad() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repo.findById(1L)).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return Optional.of(event(1L));
        });
        List<Thread> threads = new ArrayList<>();
        List<Optional<SeckillEventCache.EventWindow>> results = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 5; i++) {
                threads.add(Thread.ofVirtual().start(() -> results.add(cache.get(1L))));
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            for (Thread t : threads) t.join(Duration.ofSeconds(10));
        }
        assertThat(results).hasSize(5).allSatisfy(r -> assertThat(r).isPresent());
        verify(repo, times(1)).findById(1L);
    }

    /**
     * invalidate does not wait for an in-flight load, the next get starts a new load, and the old load's result
     * (product 10) never replaces the newer one (product 20) once it finally completes.
     */
    @Test
    void invalidateDuringAnInFlightLoadIsNotBlockedAndTheStaleResultIsNotCached() throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        AtomicReference<Thread> oldLoadThread = new AtomicReference<>();
        AtomicLong calls = new AtomicLong();
        when(repo.findById(1L)).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                oldLoadThread.set(Thread.currentThread());
                oldEntered.countDown();
                if (!releaseOld.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("old load never released");
                return Optional.of(event(1L, 10L));
            }
            return Optional.of(event(1L, 20L));
        });
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var oldGet = threads.submit(() -> cache.get(1L));
                assertThat(oldEntered.await(10, TimeUnit.SECONDS)).as("old load is blocked in findById").isTrue();

                threads.submit(() -> cache.invalidate(1L)).get(5, TimeUnit.SECONDS);   // returns while old load blocks
                assertThat(oldGet.isDone()).as("old load still in flight after invalidate returned").isFalse();

                var newGet = threads.submit(() -> cache.get(1L)).get(5, TimeUnit.SECONDS);
                assertThat(newGet).get().extracting(SeckillEventCache.EventWindow::productId).isEqualTo(20L);
                verify(repo, times(2)).findById(1L);

                releaseOld.countDown();
                assertThat(oldGet.get(10, TimeUnit.SECONDS)).as("callers of the old load get its result")
                        .get().extracting(SeckillEventCache.EventWindow::productId).isEqualTo(10L);
                // The old load's completion handling runs on its own thread; once that thread has ended it is done.
                assertThat(oldLoadThread.get().join(Duration.ofSeconds(10))).isTrue();

                assertThat(cache.get(1L)).get().extracting(SeckillEventCache.EventWindow::productId).isEqualTo(20L);
                verify(repo, times(2)).findById(1L);
            } finally {
                releaseOld.countDown();
            }
        }
    }

    /**
     * Regression (loadtest/results/H1-cache-fix/CACHE-FIX.md): a load blocked on the database must not occupy
     * the virtual-thread carriers. With the old synchronous Caffeine loader the load ran inside
     * ConcurrentHashMap.compute's monitor, so waiters for the same key pinned every carrier and no other virtual
     * thread in the JVM could run until the load finished.
     */
    @Test
    void aBlockedLoadDoesNotStarveOtherVirtualThreads() throws Exception {
        int waiters = Runtime.getRuntime().availableProcessors() * 2 + 2;   // more than the default carrier count
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repo.findById(1L)).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return Optional.of(event(1L));
        });
        CountDownLatch started = new CountDownLatch(waiters);
        CountDownLatch probe = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        boolean allStarted;
        boolean probeRan;
        try {
            threads.add(Thread.ofVirtual().start(() -> cache.get(1L)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("loader entered findById").isTrue();
            for (int i = 0; i < waiters; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    started.countDown();
                    try {
                        cache.get(1L);
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                }));
            }
            allStarted = started.await(10, TimeUnit.SECONDS);
            Thread.ofVirtual().start(probe::countDown);
            probeRan = probe.await(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            for (Thread t : threads) t.join(Duration.ofSeconds(30));
        }
        assertThat(allStarted).as("every waiter got a carrier while the load was blocked").isTrue();
        assertThat(probeRan).as("an unrelated virtual thread ran while the load was blocked").isTrue();
        assertThat(threads).noneMatch(Thread::isAlive);
        assertThat(failure.get()).isNull();
        verify(repo, times(1)).findById(1L);
    }

    @Test
    void anExpiredEntryIsReloaded() {
        AtomicLong nanos = new AtomicLong();
        cache.shutdown();
        cache = new SeckillEventCache(repo, Duration.ofSeconds(5), nanos::get);
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L)));

        cache.get(1L);
        nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        cache.get(1L);
        verify(repo, times(1)).findById(1L);

        // The async write time is stamped on completion (microseconds after the load), so check well past the TTL.
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        cache.get(1L);
        verify(repo, times(2)).findById(1L);
    }

    @Test
    void theQueryRunsOnTheCachesOwnVirtualThreadNotTheCaller() {
        AtomicReference<Thread> loadThread = new AtomicReference<>();
        when(repo.findById(1L)).thenAnswer(inv -> {
            loadThread.set(Thread.currentThread());
            return Optional.of(event(1L));
        });

        cache.get(1L);

        assertThat(loadThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(loadThread.get().isVirtual()).isTrue();
        assertThat(loadThread.get().getName()).startsWith("seckill-event-load-");
    }

    @Test
    void afterShutdownNoNewLoadIsStarted() {
        cache.shutdown();

        assertThatThrownBy(() -> cache.get(1L)).isInstanceOf(RejectedExecutionException.class);
        verify(repo, times(0)).findById(1L);
    }

    private static SeckillEvent event(Long id) {
        return event(id, 10L);
    }

    private static SeckillEvent event(Long id, Long productId) {
        return SeckillEvent.builder().id(id).productId(productId).seckillPrice(new BigDecimal("9.90"))
                .seckillStock(5).startTime(START).endTime(END).build();
    }
}
