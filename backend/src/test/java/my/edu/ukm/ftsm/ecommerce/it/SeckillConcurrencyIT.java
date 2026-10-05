package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 2000 distinct buyers race for 100 units: exactly 100 orders, no oversell, nothing left in the outbox (§6.4). */
class SeckillConcurrencyIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 10_000_000L;

    @Test
    void twoThousandBuyersGetExactlyTheStock() throws Exception {
        long eventId = createActiveEvent(createProduct(100, PRICE), 100);

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                long user = USER_BASE + i;
                futures.add(pool.submit(() -> buy(tokenFor(user), eventId)));
            }
            for (Future<HttpResponse<String>> f : futures) {
                statuses.add(f.get().statusCode());
            }
        }

        assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(100);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1900);
        awaitOrders(eventId, 100, Duration.ofSeconds(60));
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT buyer_id) FROM orders WHERE seckill_event_id = ?",
                Long.class, eventId)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM seckill_events WHERE id = ?", Integer.class, eventId))
                .isEqualTo(100);
        assertThat(redisGet(RedisKeys.seckillStock(eventId))).isEqualTo("0");
        // The relay marks rows SENT after the broker acks; the listener can persist an order slightly earlier.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxPending(eventId)).isZero());
    }
}
