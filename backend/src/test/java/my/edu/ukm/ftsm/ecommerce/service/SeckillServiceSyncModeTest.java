package my.edu.ukm.ftsm.ecommerce.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionSystemException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P3 §6.1 / §8: the SYNC benchmark mode, and that the default stays ASYNC (P3 §6.2). */
@SuppressWarnings("unchecked")
class SeckillServiceSyncModeTest {

    private static final long USER = 5L;
    private static final long EVENT = 7L;
    private static final List<String> KEYS = List.of("seckill:stock:{7}", "seckill:bought:{7}");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisScript<Long> deduct = mock(RedisScript.class);
    private final RedisScript<Long> rollback = mock(RedisScript.class);
    private final SeckillEventCache cache = mock(SeckillEventCache.class);
    private final OutboxDao outbox = mock(OutboxDao.class);
    private final SeckillOrderWriter writer = mock(SeckillOrderWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        when(cache.get(EVENT)).thenReturn(Optional.of(new SeckillEventCache.EventWindow(EVENT, 10L,
                new BigDecimal("9.90"), now.minus(1, ChronoUnit.MINUTES), now.plus(10, ChronoUnit.MINUTES))));
        when(redis.execute(deduct, KEYS, String.valueOf(USER))).thenReturn(1L);
    }

    @Test
    void syncPersistsInTheRequestThreadWithoutOutbox() {
        SeckillBuyResponse r = service("sync").buy(USER, EVENT);

        assertThat(r.result()).isEqualTo("ACCEPTED");
        ArgumentCaptor<SeckillOrderMessage> m = ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(writer).persist(m.capture());
        assertThat(m.getValue().orderId()).isEqualTo(r.trackingToken());
        assertThat(m.getValue().userId()).isEqualTo(USER);
        assertThat(m.getValue().eventId()).isEqualTo(EVENT);
        verifyNoInteractions(outbox);
    }

    @Test
    void constraintViolationWithExistingTokenIsAccepted() {
        when(writer.persist(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(writer.existsByTrackingToken(anyString())).thenReturn(true);

        assertThat(service("sync").buy(USER, EVENT).result()).isEqualTo("ACCEPTED");
        verify(redis, never()).execute(eq(rollback), eq(KEYS), anyString());
    }

    @Test
    void constraintViolationForANewTokenCompensates() {
        when(writer.persist(any())).thenThrow(new DataIntegrityViolationException("uk_orders_buyer_seckill"));
        when(writer.existsByTrackingToken(anyString())).thenReturn(false);

        SeckillBuyResponse r = service("sync").buy(USER, EVENT);

        assertThat(r.result()).isEqualTo("UNAVAILABLE");
        assertThat(r.trackingToken()).isNull();
        verify(redis).execute(rollback, KEYS, String.valueOf(USER));
    }

    @Test
    void otherFailureButOrderExistsIsAccepted() {
        when(writer.persist(any())).thenThrow(new TransactionSystemException("commit ack lost"));
        when(writer.existsByTrackingTokenSafely(anyString())).thenReturn(true);

        assertThat(service("sync").buy(USER, EVENT).result()).isEqualTo("ACCEPTED");
        verify(redis, never()).execute(eq(rollback), eq(KEYS), anyString());
    }

    @Test
    void otherFailureConfirmedAbsentCompensates() {
        when(writer.persist(any())).thenThrow(new IllegalStateException("sold_count guard rejected"));
        when(writer.existsByTrackingTokenSafely(anyString())).thenReturn(false);

        assertThat(service("sync").buy(USER, EVENT).result()).isEqualTo("UNAVAILABLE");
        verify(redis).execute(rollback, KEYS, String.valueOf(USER));
        assertThat(meters.counter("seckill.outbox.uncertain").count()).isZero();
    }

    @Test
    void otherFailureUnconfirmableDoesNotCompensate() {
        when(writer.persist(any())).thenThrow(new TransactionSystemException("connection lost during commit"));
        when(writer.existsByTrackingTokenSafely(anyString())).thenReturn(null);

        assertThat(service("sync").buy(USER, EVENT).result()).isEqualTo("UNAVAILABLE");
        verify(redis, never()).execute(eq(rollback), eq(KEYS), anyString());
        assertThat(meters.counter("seckill.outbox.uncertain").count()).isEqualTo(1.0);
    }

    @Test
    void defaultModeIsAsyncAndUsesTheOutbox() {
        SeckillBuyResponse r = serviceWithDefaultMode().buy(USER, EVENT);

        assertThat(r.result()).isEqualTo("ACCEPTED");
        verify(outbox).insert(eq(r.trackingToken()), eq(USER), eq(EVENT), anyString());
        verify(writer, never()).persist(any());
    }

    @Test
    void modeIsCaseInsensitiveAndRejectsUnknownValues() {
        service("SYNC").buy(USER, EVENT);
        verify(writer).persist(any());
        verify(outbox, never()).insert(anyString(), anyLong(), anyLong(), anyString());
        assertThatThrownBy(() -> service("batch")).isInstanceOf(IllegalArgumentException.class);
    }

    private SeckillService service(String mode) {
        return new SeckillService(mock(SeckillEventRepository.class), mock(ProductRepository.class),
                mock(OrderRepository.class), redis, deduct, rollback, cache, outbox,
                JsonMapper.builder().findAndAddModules().build(), meters, writer, mode);
    }

    /** What Spring injects when app.seckill.mode is not set: the default in the constructor's @Value. */
    private SeckillService serviceWithDefaultMode() {
        String expr = Arrays.stream(SeckillService.class.getConstructors()[0].getParameters())
                .map(p -> p.getAnnotation(Value.class)).filter(Objects::nonNull).map(Value::value)
                .filter(v -> v.startsWith("${app.seckill.mode")).findFirst().orElseThrow();
        assertThat(expr).isEqualTo("${app.seckill.mode:async}");
        return service(expr.substring(expr.indexOf(':') + 1, expr.length() - 1));
    }
}
