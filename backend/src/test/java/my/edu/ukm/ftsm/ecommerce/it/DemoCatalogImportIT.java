package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.demo.CatalogJson;
import my.edu.ukm.ftsm.ecommerce.demo.DemoCatalogImporter;
import my.edu.ukm.ftsm.ecommerce.demo.DemoCatalogImporter.OnConflict;
import my.edu.ukm.ftsm.ecommerce.demo.DemoCatalogSeeder;
import my.edu.ukm.ftsm.ecommerce.demo.DemoImportConflictException;
import my.edu.ukm.ftsm.ecommerce.demo.ImportReport;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demo-catalogue import on real MySQL (docs/phases/P5a.md §6.2, §8): first import, idempotent re-import, a conflict
 * in fail mode rolls the WHOLE transaction back, skip mode keeps the row and writes a report, and the startup guards.
 * The steps build on each other, hence the fixed order.
 */
@TestMethodOrder(OrderAnnotation.class)
@TestPropertySource(properties = "app.demo.seller-password=it-only-demo-seller-pw")   // throwaway, test container only
class DemoCatalogImportIT extends AbstractIntegrationTest {

    @Autowired
    DemoCatalogImporter importer;

    private final CatalogJson catalog = CatalogJson.load().validate();

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private long demoProducts() {
        return count("SELECT COUNT(*) FROM products WHERE id BETWEEN 1001 AND 4999");
    }

    private long autoIncrement(String table) {
        String ddl = jdbc.queryForMap("SHOW CREATE TABLE " + table).get("Create Table").toString();
        Matcher m = Pattern.compile("AUTO_INCREMENT=(\\d+)").matcher(ddl);
        assertThat(m.find()).as(ddl).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    @Order(1)
    void firstImportInsertsTheWholeCatalogue() {
        ImportReport r = importer.importCatalog(catalog, OnConflict.FAIL);
        importer.adjustAutoIncrement();

        assertThat(r.productsInserted()).isEqualTo(400);
        assertThat(r.itemsInserted()).isEqualTo(200);
        assertThat(r.sellersCreated()).isEqualTo(20);
        assertThat(r.unchanged()).isZero();
        assertThat(demoProducts()).isEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM items WHERE id BETWEEN 5001 AND 9999")).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM users WHERE email REGEXP '^seller(0[1-9]|1[0-9]|20)@siswa\\\\.ukm\\\\.edu\\\\.my$'"))
                .isEqualTo(20);
        assertThat(count("SELECT COUNT(DISTINCT seller_id) FROM items WHERE id BETWEEN 5001 AND 5200")).isEqualTo(20);
        assertThat(autoIncrement("products")).isGreaterThanOrEqualTo(10_000);
        assertThat(autoIncrement("items")).isGreaterThanOrEqualTo(10_000);
    }

    @Test
    @Order(2)
    void reImportIsUnchangedEvenAfterOrdersChangedStockAndStatus() {
        jdbc.update("UPDATE products SET total_stock = 0 WHERE id = 1003");   // what an order does
        jdbc.update("UPDATE items SET status = 'SOLD' WHERE id = 5001");

        ImportReport r = importer.importCatalog(catalog, OnConflict.FAIL);

        assertThat(r.inserted()).isZero();
        assertThat(r.unchanged()).isEqualTo(600);
        assertThat(r.sellersReused()).isEqualTo(20);
        assertThat(r.sellersCreated()).isZero();
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    @Order(3)
    void aConflictInFailModeRollsBackTheWholeImport() {
        jdbc.update("UPDATE products SET name = 'Changed by IT' WHERE id = 1001");
        jdbc.update("DELETE FROM products WHERE id = 1002");   // the import would re-insert it...

        assertThatThrownBy(() -> importer.importCatalog(catalog, OnConflict.FAIL))
                .isInstanceOf(DemoImportConflictException.class)
                .satisfies(e -> assertThat(((DemoImportConflictException) e).refs()).containsExactly("product:1001"));

        // ...but the failed import rolled back, so it is still missing, and 1001 keeps the local change
        assertThat(count("SELECT COUNT(*) FROM products WHERE id = 1002")).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = 1001", String.class))
                .isEqualTo("Changed by IT");
        assertThat(demoProducts()).isEqualTo(399);
    }

    @Test
    @Order(4)
    void skipModeKeepsTheRowAndWritesAReport() throws Exception {
        Path report = Path.of(System.getProperty("java.io.tmpdir"), "demo-import-report.json");
        Files.deleteIfExists(report);

        ImportReport r = importer.importCatalog(catalog, OnConflict.SKIP);

        assertThat(r.productsInserted()).isEqualTo(1);    // 1002 restored
        assertThat(r.unchanged()).isEqualTo(598);
        assertThat(r.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.ref()).isEqualTo("product:1001");
            assertThat(c.diffs()).containsOnlyKeys("name");
        });
        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = 1001", String.class))
                .isEqualTo("Changed by IT");
        assertThat(demoProducts()).isEqualTo(400);
        assertThat(Files.readString(report)).contains("product:1001").contains("Changed by IT");
    }

    private ApplicationContextRunner seederContext(String... properties) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=demo")
                .withPropertyValues(properties)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(DemoCatalogImporter.class, () -> importer)
                .withUserConfiguration(DemoCatalogSeeder.class);
    }

    @Test
    @Order(5)
    void startupFailsWhenTheTargetDatabaseDoesNotMatch() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        seederContext("app.demo.target-db=" + actual + "_other", "app.demo.seller-password=long-enough-password")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("APP_DEMO_TARGET_DB");
                });
        seederContext("app.demo.seller-password=long-enough-password")
                .run(ctx -> assertThat(ctx).hasFailed());
        seederContext("app.demo.target-db=" + actual, "app.demo.seller-password=long-enough-password")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(DemoCatalogSeeder.class));
    }

    @Test
    @Order(6)
    void startupFailsWithoutASellerPassword() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        for (String pw : new String[] {"", "short-11-ch"}) {
            seederContext("app.demo.target-db=" + actual, "app.demo.seller-password=" + pw).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("SEED_DEMO_PASSWORD");
            });
        }
    }
}
