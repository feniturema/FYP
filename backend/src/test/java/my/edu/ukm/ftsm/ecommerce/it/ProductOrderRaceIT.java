package my.edu.ukm.ftsm.ecommerce.it;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 50 real users order the last unit of a B2C product at once: one order, stock 0 (P2 conditional decrement, §6.4). */
class ProductOrderRaceIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 80_000_000L;

    @Test
    void lastUnitIsSoldExactlyOnce() throws Exception {
        long productId = createProduct(1, PRICE);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            createUser(USER_BASE + i);
            tokens.add(tokenFor(USER_BASE + i));
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(pool.submit(() -> order(token, "B2C_PRODUCT", productId)));
            }
            for (Future<HttpResponse<String>> f : futures) {
                statuses.add(f.get().statusCode());
            }
        }

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_stock FROM products WHERE id = ?", Integer.class, productId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE source_type = 'B2C_PRODUCT' AND ref_id = ?", Long.class, productId))
                .isEqualTo(1);
    }
}
