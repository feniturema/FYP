package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.CreateSeckillRequest;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.Role;
import my.edu.ukm.ftsm.ecommerce.model.User;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.security.JwtUtils;
import my.edu.ukm.ftsm.ecommerce.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shared fixtures for the P6a integration tests (docs/phases/P6a.md §6.3). Every test creates its own product
 * and event (UUID-suffixed names) and filters every assertion by its own event id; nothing is cleaned up.
 * Later phases may add fixture methods but must not change the meaning of these.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false", "app.seckill.relay-interval-ms=50", "app.mail.enabled=false",
        "spring.ai.model.chat=none", "spring.ai.chat.client.enabled=false", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "app.assistant.mcp-enabled=false"})   // spring.ai.* / assistant: no effect before P4b
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final BigDecimal PRICE = new BigDecimal("9.90");

    @LocalServerPort
    protected int port;

    @Autowired
    protected ProductRepository productRepository;
    @Autowired
    protected SeckillService seckillService;
    @Autowired
    protected JwtUtils jwtUtils;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;

    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** @return the new product's id */
    protected long createProduct(int totalStock, BigDecimal price) {
        Product p = productRepository.save(Product.builder()
                .name("it-product-" + UUID.randomUUID())
                .description("integration test")
                .price(price)
                .totalStock(totalStock)
                .category("it")
                .build());
        return p.getId();
    }

    /** start = now - 1 s, end = now + 10 min; then warms Redis stock and flips it ACTIVE. @return the event id */
    protected long createActiveEvent(long productId, int stock) {
        Instant now = Instant.now();
        long id = seckillService.createEvent(new CreateSeckillRequest(productId, PRICE, stock,
                now.minusSeconds(1), now.plus(10, ChronoUnit.MINUTES))).id();
        seckillService.reconcileEvents();
        return id;
    }

    /** A JWT for a user that only exists in the token (not in the users table). */
    protected String tokenFor(long userId) {
        return jwtUtils.generateToken(User.builder().id(userId).email("it" + userId + "@siswa.ukm.edu.my")
                .role(Role.STUDENT).name("it user " + userId).build());
    }

    protected HttpResponse<String> buy(String token, long eventId) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/seckill/" + eventId + "/buy"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("buy request failed for event " + eventId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /**
     * Normal checkout ({@code POST /api/orders}) used by the order-race ITs. Added in P6a next to the §6.3
     * fixtures; it does not change their meaning.
     */
    protected HttpResponse<String> order(String token, String sourceType, long refId) {
        String body = "{\"sourceType\":\"" + sourceType + "\",\"refId\":" + refId + ",\"paymentMethod\":\"FAKE_WALLET\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("order request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Authenticated JSON POST (P4b assistant ITs); {@code token} may be null. Added in P4b; existing fixtures unchanged. */
    protected HttpResponse<String> postJson(String path, String token, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("POST " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** A real STUDENT row with this id (the order-race ITs need users in the table). The hash is a placeholder. */
    protected void createUser(long id) {
        jdbc.update("INSERT INTO users (id, created_at, email, email_verified, name, password_hash, role) "
                        + "VALUES (?, NOW(6), ?, b'1', ?, 'it-placeholder-not-a-hash', 'STUDENT')",
                id, "it" + id + "@siswa.ukm.edu.my", "it user " + id);
    }

    protected void awaitOrders(long eventId, long expected, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(countOrders(eventId)).isEqualTo(expected));
    }

    protected long countOrders(long eventId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE seckill_event_id = ?", Long.class, eventId);
        return n == null ? 0 : n;
    }

    protected long outboxPending(long eventId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE event_id = ? AND status = 0",
                Long.class, eventId);
        return n == null ? 0 : n;
    }

    protected String redisGet(String key) {
        return redis.opsForValue().get(key);
    }
}
