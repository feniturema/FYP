package my.edu.ukm.ftsm.ecommerce.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/phases/P4b.md §7.7, in this exact order: (1) start MySQL and Redis containers; (2) run backend's migrations with
 * the test-scoped Flyway as root; (3) seed fixed-id rows as root and create the SELECT-only account the server uses;
 * (4) point the server at both containers through {@link DynamicPropertySource}; (5) start the server and check that it
 * ran no Flyway, validated the schema, and answers MCP initialize / tools/list / tools/call.
 * No host database, Redis, compose project or .env is used.
 */
@SpringBootTest(classes = McpServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIT {

    private static final Logger log = LoggerFactory.getLogger(McpServerIT.class);

    static final long PRODUCT_ID = 9001, EVENT_ID = 9101, ITEM_ID = 9201, SELLER_ID = 9301;
    static final int TOTAL_STOCK = 7, REDIS_REMAINING = 3;
    static final Set<String> TOOLS =
            Set.of("search_products", "search_secondhand_items", "get_product_detail", "get_stock", "list_flash_sales");

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.46"));
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:8.10.2"));
    /** Random per run; matches the §6.8 whitelist ^[A-Za-z0-9._~-]{24,128}$; never logged. */
    static final String RO_PASSWORD = "it-" + HexFormat.of().formatHex(randomBytes(16));
    static final List<String> MIGRATED;

    static {
        MYSQL.start();                                   // (1)
        REDIS.start();
        MIGRATED = migrate();                            // (2)
        seed();                                          // (3)
    }

    @DynamicPropertySource                               // (4)
    static void containers(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("MCP_DB_USERNAME", () -> "ftsm_ro");
        r.add("MCP_DB_PASSWORD", () -> RO_PASSWORD);
        r.add("REDIS_HOST", REDIS::getHost);
        r.add("REDIS_PORT", () -> REDIS.getFirstMappedPort());
    }

    @LocalServerPort
    int port;
    @Autowired
    ApplicationContext context;
    @Autowired
    Environment environment;

    @Test
    void serverValidatesTheSchemaWithoutFlywayAndServesTheFiveTools() throws Exception {
        // (5) the server ran no Flyway and validated the schema migrated in (2)
        assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flywayVersions()).isEqualTo(MIGRATED);

        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp").connectTimeout(Duration.ofSeconds(3)).build())
                .requestTimeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(5)).build();
        try {
            client.initialize();
            Set<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
            assertThat(names).isEqualTo(TOOLS);

            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest("get_stock", Map.of("productId", PRODUCT_ID)));
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            JsonNode stock = new ObjectMapper().readTree(((McpSchema.TextContent) result.content().get(0)).text());
            assertThat(stock.get("productId").asLong()).isEqualTo(PRODUCT_ID);
            assertThat(stock.get("totalStock").asInt()).isEqualTo(TOTAL_STOCK);
            assertThat(stock.get("flashSale").get("eventId").asLong()).isEqualTo(EVENT_ID);
            assertThat(stock.get("flashSale").get("remaining").asInt()).isEqualTo(REDIS_REMAINING);
        } finally {
            client.closeGracefully();
        }
    }

    /** Tries ${user.dir}/../backend/... first, then ${user.dir}/backend/...; fails with both paths otherwise. */
    static Path migrationDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = {cwd.resolve("../backend/src/main/resources/db/migration").normalize(),
                cwd.resolve("backend/src/main/resources/db/migration").normalize()};
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("backend migrations not found; tried " + candidates[0] + " and " + candidates[1]);
    }

    private static List<String> migrate() {
        Path dir = migrationDir();
        log.info("[McpServerIT] migrating with backend migrations from {}", dir);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())
                .locations("filesystem:" + dir).load().migrate();
        List<String> versions = flywayVersions();
        log.info("[McpServerIT] Flyway applied versions {}", versions);
        return versions;
    }

    private static List<String> flywayVersions() {
        try (Connection c = root(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM flyway_schema_history WHERE success = 1 "
                     + "AND version IS NOT NULL ORDER BY installed_rank")) {
            List<String> v = new ArrayList<>();
            while (rs.next()) {
                v.add(rs.getString(1));
            }
            return v;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read flyway_schema_history", e);
        }
    }

    private static void seed() {
        String start = LocalDateTime.now(ZoneOffset.UTC).minusHours(1).toString();   // Hibernate maps Instant as UTC
        String end = LocalDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
        String db = MYSQL.getDatabaseName();
        try (Connection c = root(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO products (id, category, created_at, description, image_url, name, price, total_stock) "
                    + "VALUES (" + PRODUCT_ID + ", 'Apparel', NOW(6), 'IT seed product', NULL, 'IT FTSM Hoodie', 59.90, "
                    + TOTAL_STOCK + ")");
            s.execute("INSERT INTO seckill_events (id, end_time, product_id, seckill_price, seckill_stock, start_time, "
                    + "status, stock_warmed, sold_count, reconciled) VALUES (" + EVENT_ID + ", '" + end + "', " + PRODUCT_ID
                    + ", 39.90, 5, '" + start + "', 'ACTIVE', b'1', 2, b'0')");
            s.execute("INSERT INTO items (id, category, item_condition, created_at, description, image_url, price, "
                    + "seller_id, status, title) VALUES (" + ITEM_ID + ", 'Stationery', 'GOOD', NOW(6), 'IT seed item', NULL, "
                    + "20.00, " + SELLER_ID + ", 'ACTIVE', 'IT used calculator')");
            s.execute("INSERT INTO reviews (author_id, comment, created_at, rating, target_ref_id, target_type) VALUES "
                    + "(" + SELLER_ID + ", 'ok', NOW(6), 4, " + PRODUCT_ID + ", 'PRODUCT'), "
                    + "(" + SELLER_ID + ", 'good', NOW(6), 5, " + PRODUCT_ID + ", 'PRODUCT')");
            s.execute("CREATE USER 'ftsm_ro'@'%' IDENTIFIED BY '" + RO_PASSWORD + "'");
            s.execute("GRANT SELECT ON `" + db + "`.* TO 'ftsm_ro'@'%'");
        } catch (Exception e) {
            throw new IllegalStateException("seeding failed", e);
        }
        RedisClient redis = RedisClient.create(REDIS.getRedisURI());
        try (StatefulRedisConnection<String, String> conn = redis.connect()) {
            conn.sync().set("seckill:stock:{" + EVENT_ID + "}", String.valueOf(REDIS_REMAINING));
        } finally {
            redis.shutdown();
        }
        log.info("[McpServerIT] seeded product {}, flash sale {} (redis remaining {}), item {}, read-only user ftsm_ro",
                PRODUCT_ID, EVENT_ID, REDIS_REMAINING, ITEM_ID);
    }

    private static Connection root() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        ThreadLocalRandom.current().nextBytes(b);
        return b;
    }
}
