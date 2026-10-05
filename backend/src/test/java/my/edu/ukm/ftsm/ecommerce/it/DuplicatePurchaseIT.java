package my.edu.ukm.ftsm.ecommerce.it;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** One user fires 20 concurrent buys: exactly one is accepted, the rest are ALREADY_BOUGHT, one order (§6.4). */
class DuplicatePurchaseIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 20_000_000L;

    @Test
    void sameUserConcurrentBuysYieldOneOrder() throws Exception {
        long eventId = createActiveEvent(createProduct(10, PRICE), 10);
        String token = tokenFor(USER_BASE + 1);

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> buy(token, eventId)));
            }
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get());
            }
        }

        assertThat(responses).filteredOn(r -> r.statusCode() == 202).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() == 409).hasSize(19)
                .allSatisfy(r -> assertThat(r.body()).contains("\"ALREADY_BOUGHT\""));
        awaitOrders(eventId, 1, Duration.ofSeconds(60));
    }
}
