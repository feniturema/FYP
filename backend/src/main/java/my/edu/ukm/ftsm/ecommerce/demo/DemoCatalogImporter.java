package my.edu.ukm.ftsm.ecommerce.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import my.edu.ukm.ftsm.ecommerce.demo.CatalogJson.ItemEntry;
import my.edu.ukm.ftsm.ecommerce.demo.CatalogJson.ProductEntry;
import my.edu.ukm.ftsm.ecommerce.demo.ImportReport.Conflict;
import my.edu.ukm.ftsm.ecommerce.search.CatalogCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Atomic demo-catalogue import (docs/phases/P5a.md §6.2). {@link #importCatalog} runs in ONE transaction: sellers,
 * products and items. A row that already exists with the same content is "unchanged"; one with different content is a
 * conflict, handled by {@code app.demo.on-conflict} (fail = roll everything back, skip = keep the row and report it).
 * It is a separate bean from {@link DemoCatalogSeeder} so the call goes through the transactional proxy.
 *
 * <p>Content comparison covers what the catalogue defines and the shop does not change by itself: name/title,
 * description, price, category, image, and for items condition and seller. {@code total_stock} and item
 * {@code status} are excluded because orders change them; otherwise any demo purchase would block the next restart.
 */
@Component
public class DemoCatalogImporter {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogImporter.class);
    public static final int SELLERS = 20;
    static final String REPORT_FILE = "demo-import-report.json";

    public enum OnConflict { FAIL, SKIP;

        public static OnConflict parse(String v) {
            return valueOf((v == null || v.isBlank() ? "fail" : v.strip()).toUpperCase(Locale.ROOT));
        }
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String sellerPassword;
    private final String onConflict;

    public DemoCatalogImporter(JdbcTemplate jdbc, PasswordEncoder encoder,
                               @Value("${app.demo.seller-password:}") String sellerPassword,
                               @Value("${app.demo.on-conflict:fail}") String onConflict) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.sellerPassword = sellerPassword;
        this.onConflict = onConflict;
    }

    @Transactional
    public ImportReport importCatalog(CatalogJson catalog) {
        return importCatalog(catalog, OnConflict.parse(onConflict));
    }

    @Transactional
    public ImportReport importCatalog(CatalogJson catalog, OnConflict mode) {
        catalog.validate();
        if (sellerPassword == null || sellerPassword.length() < 12) {
            throw new IllegalStateException("SEED_DEMO_PASSWORD must be at least 12 characters");
        }
        int[] sellerCounts = new int[2];   // created, reused
        Map<String, Long> sellers = sellers(sellerCounts);

        int productsInserted = 0;
        int itemsInserted = 0;
        int unchanged = 0;
        List<Conflict> conflicts = new ArrayList<>();
        for (ProductEntry p : catalog.products()) {
            Map<String, String> want = productContent(p);
            Map<String, String> have = existing("SELECT name, description, price, category, image_url FROM products "
                    + "WHERE id = ? FOR UPDATE", p.id(), "name", "description", "price", "category", "imageUrl");
            if (have == null) {
                jdbc.update("INSERT INTO products (id, category, created_at, description, image_url, name, price, "
                                + "total_stock) VALUES (?, ?, NOW(6), ?, ?, ?, ?, ?)",
                        p.id(), want.get("category"), p.description(), p.imageUrl(), p.name(), p.price(), p.totalStock());
                productsInserted++;
            } else if (have.equals(want)) {
                unchanged++;
            } else {
                conflicts.add(new Conflict("product:" + p.id(), diff(have, want)));
            }
        }
        for (ItemEntry i : catalog.items()) {
            Map<String, String> want = itemContent(i, sellers.get(i.sellerKey()));
            Map<String, String> have = existing("SELECT title, description, price, category, image_url, item_condition, "
                            + "seller_id FROM items WHERE id = ? FOR UPDATE", i.id(),
                    "title", "description", "price", "category", "imageUrl", "condition", "sellerId");
            if (have == null) {
                jdbc.update("INSERT INTO items (id, category, item_condition, created_at, description, image_url, price, "
                                + "seller_id, status, title) VALUES (?, ?, ?, NOW(6), ?, ?, ?, ?, 'ACTIVE', ?)",
                        i.id(), want.get("category"), i.condition(), i.description(), i.imageUrl(), i.price(),
                        sellers.get(i.sellerKey()), i.title());
                itemsInserted++;
            } else if (have.equals(want)) {
                unchanged++;
            } else {
                conflicts.add(new Conflict("item:" + i.id(), diff(have, want)));
            }
        }

        if (!conflicts.isEmpty() && mode == OnConflict.FAIL) {
            throw new DemoImportConflictException(conflicts.stream().map(Conflict::ref).toList());
        }
        ImportReport report = new ImportReport(sellerCounts[0], sellerCounts[1], productsInserted, itemsInserted,
                unchanged, List.copyOf(conflicts));
        if (!conflicts.isEmpty()) {
            Path file = writeReport(report);
            log.warn("[Demo] {} existing rows differ from the catalogue and were kept (on-conflict=skip): {}; report {}",
                    conflicts.size(), conflicts.stream().map(Conflict::ref).toList(), file);
        }
        return report;
    }

    /**
     * Moves both AUTO_INCREMENT counters past the demo id ranges. ALTER TABLE commits implicitly, so this runs after
     * the import transaction and is not part of it; MySQL keeps max(current, 10000), so it is idempotent.
     */
    public void adjustAutoIncrement() {
        jdbc.execute("ALTER TABLE products AUTO_INCREMENT = 10000");
        jdbc.execute("ALTER TABLE items AUTO_INCREMENT = 10000");
    }

    static Path reportPath() {
        return Path.of(System.getProperty("java.io.tmpdir"), REPORT_FILE);
    }

    /** seller01..seller20 by email; ids are read back, never assumed contiguous. */
    private Map<String, Long> sellers(int[] counts) {
        Map<String, Long> ids = new HashMap<>();
        for (int n = 1; n <= SELLERS; n++) {
            String key = String.format("seller%02d", n);
            String email = key + "@siswa.ukm.edu.my";
            List<Long> found = jdbc.queryForList("SELECT id FROM users WHERE email = ? FOR UPDATE", Long.class, email);
            if (found.isEmpty()) {
                jdbc.update("INSERT INTO users (created_at, email, email_verified, name, password_hash, role) "
                        + "VALUES (NOW(6), ?, b'1', ?, ?, 'STUDENT')", email, "Demo Seller " + n,
                        encoder.encode(sellerPassword));
                found = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email);
                counts[0]++;
            } else {
                counts[1]++;
            }
            ids.put(key, found.get(0));
        }
        return ids;
    }

    private Map<String, String> existing(String sql, long id, String... fields) {
        List<Map<String, String>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < fields.length; c++) {
                Object v = rs.getObject(c + 1);
                m.put(fields[c], "price".equals(fields[c]) ? money((BigDecimal) v) : Objects.toString(v, null));
            }
            return m;
        }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, String> productContent(ProductEntry p) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("price", money(p.price()));
        m.put("category", CatalogCategory.parse(p.category()).orElseThrow().label());
        m.put("imageUrl", p.imageUrl());
        return m;
    }

    private static Map<String, String> itemContent(ItemEntry i, Long sellerId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", i.title());
        m.put("description", i.description());
        m.put("price", money(i.price()));
        m.put("category", CatalogCategory.parse(i.category()).orElseThrow().label());
        m.put("imageUrl", i.imageUrl());
        m.put("condition", i.condition());
        m.put("sellerId", String.valueOf(sellerId));
        return m;
    }

    private static String money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static Map<String, List<String>> diff(Map<String, String> have, Map<String, String> want) {
        Map<String, List<String>> d = new LinkedHashMap<>();
        want.forEach((k, v) -> {
            if (!Objects.equals(have.get(k), v)) {
                d.put(k, java.util.Arrays.asList(have.get(k), v));
            }
        });
        return d;
    }

    private static Path writeReport(ImportReport report) {
        Path file = reportPath();
        try {
            Files.writeString(file, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(report));
        } catch (IOException e) {
            log.warn("[Demo] could not write {}: {}", file, e.toString());
        }
        return file;
    }
}
