package my.edu.ukm.ftsm.ecommerce.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.search.CatalogCategory;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService.Mode;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery.Type;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchHit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FULLTEXT (ngram, V3) keyword search on real MySQL 8.0.46 (docs/phases/P5a.md §6.3, §8): English, Malay and Chinese
 * products, a SOLD item, filters and a stable order. Other ITs share the database, so assertions are about this
 * test's own rows (contains / does not contain) except where a query cannot match anything by construction.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogSearchIT extends AbstractIntegrationTest {

    @Autowired
    HybridSearchService search;

    private long hoodieEn;
    private long kasutMs;
    private long earphonesZh;
    private long flaskZh;
    private long standZh;
    private long soldItem;

    @BeforeAll
    void seed() {
        hoodieEn = product("Navy Hoodie", "Warm fleece hoodie with the faculty logo", "79.00", CatalogCategory.APPAREL);
        product("Wireless Mouse", "Silent clicks, USB receiver", "45.00", CatalogCategory.ELECTRONICS);
        product("Campus Notebook", "A5 ruled notebook, 120 pages", "8.00", CatalogCategory.STATIONERY);
        kasutMs = product("Kasut Sukan Putih", "Kasut larian ringan untuk latihan harian", "120.00",
                CatalogCategory.SPORTS);
        product("Beg Galas Hitam", "Beg galas kalis air untuk komputer riba", "65.00",
                CatalogCategory.ACCESSORIES);
        product("Botol Air Keluli", "Botol air keluli tahan karat 750ml", "30.00", CatalogCategory.LIFESTYLE);
        earphonesZh = product("降噪蓝牙耳机", "无线蓝牙耳机，续航三十小时", "199.00", CatalogCategory.ELECTRONICS);
        flaskZh = product("不锈钢保温杯", "保温十二小时，适合上课携带", "39.00", CatalogCategory.LIFESTYLE);
        standZh = product("笔记本电脑支架", "铝合金折叠支架，可调高度", "59.00", CatalogCategory.ELECTRONICS);
        jdbc.update("INSERT INTO items (category, item_condition, created_at, description, image_url, price, seller_id, "
                        + "status, title) VALUES ('Apparel', 'USED', NOW(6), 'Second-hand hoodie, worn twice', NULL, "
                        + "20.00, 1, 'SOLD', 'Used Hoodie Grey')");
        soldItem = jdbc.queryForObject("SELECT MAX(id) FROM items WHERE title = 'Used Hoodie Grey'", Long.class);
    }

    private long product(String name, String description, String price, CatalogCategory category) {
        return productRepository.save(Product.builder().name(name).description(description)
                .price(new BigDecimal(price)).totalStock(10).category(category.label()).build()).getId();
    }

    private SearchResult run(String q, BigDecimal maxPrice, CatalogCategory category, Type type) {
        return search.search(new SearchQuery(q, maxPrice, category, type, 20, Mode.KEYWORD, false));
    }

    private static List<String> refs(SearchResult r) {
        return r.hits().stream().map(SearchHit::ref).toList();
    }

    @Test
    void englishMalayAndChineseKeywordsHitTheirProducts() {
        assertThat(refs(run("hoodie", null, null, Type.ALL))).contains("product:" + hoodieEn)
                .doesNotContain("product:" + kasutMs, "product:" + earphonesZh);
        assertThat(refs(run("kasut", null, null, Type.ALL))).contains("product:" + kasutMs)
                .doesNotContain("product:" + hoodieEn);
        assertThat(refs(run("耳机", null, null, Type.ALL))).contains("product:" + earphonesZh)
                .doesNotContain("product:" + flaskZh, "product:" + hoodieEn);
    }

    @Test
    void aSingleChineseCharacterIsShorterThanTheNgramAndMatchesNothing() {
        assertThat(run("耳", null, null, Type.ALL).hits()).isEmpty();
        assertThat(run("杯", null, null, Type.ALL).hits()).isEmpty();
    }

    @Test
    void maxPriceAndCategoryFilter() {
        assertThat(refs(run("hoodie", new BigDecimal("50.00"), null, Type.ALL))).doesNotContain("product:" + hoodieEn);
        assertThat(refs(run("hoodie", new BigDecimal("79.00"), null, Type.ALL))).contains("product:" + hoodieEn);
        assertThat(refs(run("hoodie", null, CatalogCategory.ELECTRONICS, Type.ALL))).doesNotContain("product:" + hoodieEn);
        assertThat(refs(run("hoodie", null, CatalogCategory.APPAREL, Type.ALL))).contains("product:" + hoodieEn);
        assertThat(refs(run("支架", null, CatalogCategory.ELECTRONICS, Type.PRODUCT))).contains("product:" + standZh);
    }

    @Test
    void soldItemsAreNeverReturned() {
        assertThat(refs(run("hoodie", null, null, Type.ALL))).doesNotContain("item:" + soldItem);
        assertThat(refs(run("hoodie", null, null, Type.ITEM))).doesNotContain("item:" + soldItem);
    }

    @Test
    void theSameQueryReturnsTheSameOrder() {
        for (String q : new String[] {"hoodie", "kasut", "蓝牙耳机", "botol air"}) {
            SearchResult a = run(q, null, null, Type.ALL);
            SearchResult b = run(q, null, null, Type.ALL);
            assertThat(refs(a)).isNotEmpty().isEqualTo(refs(b));
            assertThat(a.hits()).extracting(SearchHit::score).isEqualTo(b.hits().stream().map(SearchHit::score).toList());
        }
    }

    @Test
    void anonymousHttpSearchUsesTheSameEngine() throws Exception {
        String q = URLEncoder.encode("kasut", StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/search?q=" + q + "&limit=5"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(r.body());
        assertThat(body.path("effectiveMode").asText()).isEqualTo("KEYWORD");
        assertThat(body.path("hits").findValuesAsText("ref")).contains("product:" + kasutMs);

        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/search?q=")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(400);
    }
}
