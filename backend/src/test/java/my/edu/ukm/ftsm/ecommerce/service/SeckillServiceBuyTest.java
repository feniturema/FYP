package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillBuyResponse;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillOrderMessage;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SeckillServiceBuyTest {

    private static final long USER = 5L;
    private static final long EVENT = 7L;
    private static final List<String> KEYS = List.of("seckill:stock:{7}", "seckill:bought:{7}");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisScript<Long> deduct = mock(RedisScript.class);
    private final RedisScript<Long> rollback = mock(RedisScript.class);
    private final SeckillEventCache cache = mock(SeckillEventCache.class);
    private final OutboxDao outbox = mock(OutboxDao.class);
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SeckillService service;

    @BeforeEach
    void setUp() {
        service = new SeckillService(mock(SeckillEventRepository.class), mock(ProductRepository.class),
                mock(OrderRepository.class), redis, deduct, rollback, cache, outbox, mapper, meters);
        Instant now = Instant.now();
        activeWindow(now.minus(1, ChronoUnit.MINUTES), now.plus(10, ChronoUnit.MINUTES));
    }

    @Test
    void acceptedWritesTheOutboxRowAndReturnsItsOrderId() throws Exception {
        deductReturns(1L);

        SeckillBuyResponse r = service.buy(USER, EVENT);

        assertThat(r.result()).isEqualTo("ACCEPTED");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).insert(eq(r.trackingToken()), eq(USER), eq(EVENT), payload.capture());
        SeckillOrderMessage m = mapper.readValue(payload.getValue(), SeckillOrderMessage.class);
        assertThat(m.orderId()).isEqualTo(r.trackingToken());
        assertThat(m.userId()).isEqualTo(USER);
        assertThat(m.eventId()).isEqualTo(EVENT);
        assertThat(m.productId()).isEqualTo(10L);
        assertThat(m.price()).isEqualByComparingTo("9.90");
        assertThat(m.acceptedAt()).isNotNull();
    }

    @Test
    void soldOut() {
        deductReturns(0L);
        assertResult("SOLD_OUT");
    }

    @Test
    void alreadyBought() {
        deductReturns(-1L);
        assertResult("ALREADY_BOUGHT");
    }

    @Test
    void notWarmed() {
        deductReturns(-2L);
        assertResult("NOT_ACTIVE");
    }

    @Test
    void outsideTheWindowDoesNotTouchRedis() {
        Instant now = Instant.now();
        activeWindow(now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS));

        assertResult("NOT_ACTIVE");
        verify(redis, never()).execute(eq(deduct), eq(KEYS), anyString());
    }

    @Test
    void unknownEventIsNotActive() {
        when(cache.get(EVENT)).thenReturn(Optional.empty());
        assertResult("NOT_ACTIVE");
    }

    @Test
    void insertFailedAndConfirmedAbsentCompensatesAndReturnsUnavailable() {
        deductReturns(1L);
        insertFails();
        when(outbox.existsByOrderIdSafely(anyString())).thenReturn(false);
        when(redis.execute(rollback, KEYS, String.valueOf(USER))).thenReturn(1L);

        assertResult("UNAVAILABLE");
        verify(redis).execute(rollback, KEYS, String.valueOf(USER));
        assertThat(meters.counter("seckill.compensation.failed").count()).isZero();
    }

    @Test
    void insertFailedButRowExistsIsAccepted() {
        deductReturns(1L);
        insertFails();
        when(outbox.existsByOrderIdSafely(anyString())).thenReturn(true);

        SeckillBuyResponse r = service.buy(USER, EVENT);

        assertThat(r.result()).isEqualTo("ACCEPTED");
        assertThat(r.trackingToken()).isNotNull();
        verify(redis, never()).execute(eq(rollback), eq(KEYS), anyString());
    }

    @Test
    void insertFailedAndUnconfirmableDoesNotCompensate() {
        deductReturns(1L);
        insertFails();
        when(outbox.existsByOrderIdSafely(anyString())).thenReturn(null);

        assertResult("UNAVAILABLE");
        verify(redis, never()).execute(eq(rollback), eq(KEYS), anyString());
        assertThat(meters.counter("seckill.outbox.uncertain").count()).isEqualTo(1.0);
    }

    @Test
    void compensationFailureIsCountedAndStillUnavailable() {
        deductReturns(1L);
        insertFails();
        when(outbox.existsByOrderIdSafely(anyString())).thenReturn(false);
        when(redis.execute(rollback, KEYS, String.valueOf(USER)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertResult("UNAVAILABLE");
        assertThat(meters.counter("seckill.compensation.failed").count()).isEqualTo(1.0);
    }

    private void activeWindow(Instant start, Instant end) {
        when(cache.get(EVENT)).thenReturn(Optional.of(
                new SeckillEventCache.EventWindow(EVENT, 10L, new BigDecimal("9.90"), start, end)));
    }

    private void deductReturns(long code) {
        when(redis.execute(deduct, KEYS, String.valueOf(USER))).thenReturn(code);
    }

    private void insertFails() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(outbox).insert(anyString(), anyLong(), anyLong(), anyString());
    }

    private void assertResult(String expected) {
        SeckillBuyResponse r = service.buy(USER, EVENT);
        assertThat(r.result()).isEqualTo(expected);
        if (!"ACCEPTED".equals(expected)) {
            assertThat(r.trackingToken()).isNull();
        }
    }
}
