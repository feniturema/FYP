package my.edu.ukm.ftsm.ecommerce.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.service.SeckillReconciler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SeckillReconcilerTest {

    private static final long ID = 7L;
    private static final String STOCK_KEY = "seckill:stock:{7}";
    private static final Instant END = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);

    private final SeckillEventRepository events = mock(SeckillEventRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OutboxDao outbox = mock(OutboxDao.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void withinTwoMinutesOfTheEndNothingIsChecked() {
        SeckillEvent e = event(5, 5);

        assertThat(reconcilerAt(END.plusSeconds(60)).reconcile(e)).isEqualTo(Outcome.SKIPPED);

        verify(outbox, never()).countAllForEvent(ID);
        assertThat(e.isReconciled()).isFalse();
    }

    @Test
    void redisErrorDoesNotMarkAndEndsTheRound() {
        SeckillEvent e = event(5, 5);
        numbers(0, 5, 5);
        when(values.get(STOCK_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        when(events.findByStatusAndReconciledFalse(SeckillEvent.Status.ENDED)).thenReturn(List.of(e, event(5, 5)));

        reconcilerAt(END.plusSeconds(300)).run();

        assertThat(e.isReconciled()).isFalse();
        verify(events, never()).markReconciled(any());
        verify(values, org.mockito.Mockito.times(1)).get(STOCK_KEY);
    }

    @Test
    void consistentNumbersAreMarkedWithoutCounters() {
        SeckillEvent e = event(5, 5);
        numbers(0, 5, 5);
        when(values.get(STOCK_KEY)).thenReturn("0");

        assertThat(reconcilerAt(END.plusSeconds(300)).reconcile(e)).isEqualTo(Outcome.CONSISTENT);

        assertMarked(e);
        assertThat(meters.counter("seckill.reconcile.mismatch").count()).isZero();
        assertThat(meters.counter("seckill.reconcile.incomplete").count()).isZero();
    }

    @Test
    void lostSlotsAreAMismatch() {
        SeckillEvent e = event(5, 4);
        numbers(0, 4, 4);
        when(values.get(STOCK_KEY)).thenReturn("0");   // redis sold 5, only 4 orders: undersell

        assertThat(reconcilerAt(END.plusSeconds(300)).reconcile(e)).isEqualTo(Outcome.MISMATCH);

        assertMarked(e);
        assertThat(meters.counter("seckill.reconcile.mismatch").count()).isEqualTo(1.0);
    }

    @Test
    void missingRedisKeyIsIncomplete() {
        SeckillEvent e = event(5, 5);
        numbers(0, 5, 5);
        when(values.get(STOCK_KEY)).thenReturn(null);

        assertThat(reconcilerAt(END.plusSeconds(300)).reconcile(e)).isEqualTo(Outcome.INCOMPLETE);

        assertMarked(e);
        assertThat(meters.counter("seckill.reconcile.incomplete").count()).isEqualTo(1.0);
    }

    @Test
    void backlogWithinGraceWaits() {
        SeckillEvent e = event(5, 3);
        numbers(2, 5, 3);
        when(values.get(STOCK_KEY)).thenReturn("0");

        assertThat(reconcilerAt(END.plus(GRACE).minusSeconds(1)).reconcile(e)).isEqualTo(Outcome.WAITING);

        assertThat(e.isReconciled()).isFalse();
        verify(events, never()).markReconciled(any());
    }

    @Test
    void backlogPastGraceIsMarkedIncomplete() {
        SeckillEvent e = event(5, 3);
        numbers(2, 5, 3);
        when(values.get(STOCK_KEY)).thenReturn("0");

        assertThat(reconcilerAt(END.plus(GRACE)).reconcile(e)).isEqualTo(Outcome.INCOMPLETE);

        assertMarked(e);
        assertThat(meters.counter("seckill.reconcile.incomplete").count()).isEqualTo(1.0);
    }

    @Test
    void soldCountIsReReadAndTheMarkNeverWritesItBack() {
        SeckillEvent stale = event(5, 4);                 // loaded before the 5th order committed
        numbers(0, 5, 5);
        when(events.findById(ID)).thenReturn(Optional.of(event(5, 5)));
        when(values.get(STOCK_KEY)).thenReturn("0");

        assertThat(reconcilerAt(END.plusSeconds(300)).reconcile(stale)).isEqualTo(Outcome.CONSISTENT);

        verify(events).markReconciled(ID);
        verify(events, never()).save(any());
    }

    @Test
    void janitorDeletesSentRowsOlderThanThreeDays() {
        Instant now = Instant.parse("2026-10-07T10:17:00Z");
        new OutboxJanitor(outbox, Clock.fixed(now, ZoneOffset.UTC)).purge();

        verify(outbox).deleteSentReconciledBefore(now.minus(Duration.ofDays(3)));
    }

    private SeckillReconciler reconcilerAt(Instant now) {
        return new SeckillReconciler(events, orders, outbox, redis, meters, GRACE, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void numbers(long pending, long published, long orderCount) {
        when(outbox.countPendingForEvent(any(Long.class))).thenReturn(pending);
        when(outbox.countAllForEvent(any(Long.class))).thenReturn(published);
        when(orders.countBySeckillEventId(any(Long.class))).thenReturn(orderCount);
    }

    private void assertMarked(SeckillEvent e) {
        assertThat(e.isReconciled()).isTrue();
        verify(events).markReconciled(ID);
        verify(events, never()).save(any());
    }

    private static SeckillEvent event(int stock, int sold) {
        return SeckillEvent.builder().id(ID).productId(10L).seckillPrice(BigDecimal.TEN).seckillStock(stock)
                .soldCount(sold).startTime(END.minusSeconds(600)).endTime(END)
                .status(SeckillEvent.Status.ENDED).build();
    }
}
