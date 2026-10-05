package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * The outbox INSERT fails and the row is confirmed absent: 503, the Redis slot is given back (stock and bought
 * set unchanged), and the same user can buy again once the database works (§6.4).
 */
class OutboxCompensationIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 60_000_000L;

    @MockitoSpyBean
    OutboxDao outboxDao;

    @Test
    void failedInsertIsCompensatedAndTheUserCanBuyAgain() {
        long eventId = createActiveEvent(createProduct(5, PRICE), 5);
        long user = USER_BASE + 1;
        String token = tokenFor(user);

        try {
            doThrow(new DataAccessResourceFailureException("injected: outbox insert failed"))
                    .when(outboxDao).insert(anyString(), anyLong(), eq(eventId), anyString());
            doReturn(false).when(outboxDao).existsByOrderIdSafely(anyString());

            assertThat(buy(token, eventId).statusCode()).isEqualTo(503);
            assertThat(redisGet(RedisKeys.seckillStock(eventId))).isEqualTo("5");
            assertThat(redis.opsForSet().isMember(RedisKeys.seckillBought(eventId), String.valueOf(user))).isFalse();
        } finally {
            Mockito.reset(outboxDao);
        }

        assertThat(buy(token, eventId).statusCode()).isEqualTo(202);
        awaitOrders(eventId, 1, Duration.ofSeconds(60));
    }
}
