package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 20 real users buy one C2C item at once: one order, the item ends SOLD (P2 conditional ACTIVE→SOLD, §6.4). */
class ItemOrderRaceIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 90_000_000L;

    @Autowired
    ItemRepository itemRepository;

    @Test
    void itemIsSoldExactlyOnce() throws Exception {
        long sellerId = USER_BASE;
        createUser(sellerId);
        long itemId = itemRepository.save(Item.builder()
                .sellerId(sellerId)
                .title("it-item-" + UUID.randomUUID())
                .description("integration test")
                .price(PRICE)
                .category("it")
                .condition("GOOD")
                .status(Item.Status.ACTIVE)
                .build()).getId();
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            createUser(USER_BASE + i);
            tokens.add(tokenFor(USER_BASE + i));
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(pool.submit(() -> order(token, "C2C_ITEM", itemId)));
            }
            for (Future<HttpResponse<String>> f : futures) {
                statuses.add(f.get().statusCode());
            }
        }

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM items WHERE id = ?", String.class, itemId)).isEqualTo("SOLD");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE source_type = 'C2C_ITEM' AND ref_id = ?", Long.class, itemId))
                .isEqualTo(1);
    }
}
