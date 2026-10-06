package my.edu.ukm.ftsm.ecommerce.search;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;

/**
 * FULLTEXT (ngram) recall over V3's {@code ft_products} / {@code ft_items} (docs/phases/P5a.md §6.3). Each arm returns
 * at most {@link #ARM_LIMIT} rows ordered by relevance, then id. Items are ACTIVE only.
 */
@Component
public class KeywordRecall {

    public static final int ARM_LIMIT = 20;

    public record ProductRow(long id, double score) {}

    public record ItemRow(long id, double score) {}

    private static final String PRODUCTS = """
            SELECT id, MATCH(name, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE) AS score
            FROM products
            WHERE MATCH(name, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE)
              AND (:maxPrice IS NULL OR price <= :maxPrice)
              AND (:category IS NULL OR category = :category)
            ORDER BY score DESC, id ASC
            LIMIT 20""";

    private static final String ITEMS = """
            SELECT id, MATCH(title, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE) AS score
            FROM items
            WHERE MATCH(title, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE)
              AND (:maxPrice IS NULL OR price <= :maxPrice)
              AND (:category IS NULL OR category = :category)
              AND status = 'ACTIVE'
            ORDER BY score DESC, id ASC
            LIMIT 20""";

    private final NamedParameterJdbcTemplate jdbc;

    public KeywordRecall(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param category stored label (e.g. "Apparel") or null */
    public List<ProductRow> products(String q, BigDecimal maxPrice, String category) {
        return jdbc.query(PRODUCTS, params(q, maxPrice, category),
                (rs, n) -> new ProductRow(rs.getLong("id"), rs.getDouble("score")));
    }

    /** @param category stored label (e.g. "Books") or null */
    public List<ItemRow> items(String q, BigDecimal maxPrice, String category) {
        return jdbc.query(ITEMS, params(q, maxPrice, category),
                (rs, n) -> new ItemRow(rs.getLong("id"), rs.getDouble("score")));
    }

    private static MapSqlParameterSource params(String q, BigDecimal maxPrice, String category) {
        return new MapSqlParameterSource()
                .addValue("q", q, Types.VARCHAR)
                .addValue("maxPrice", maxPrice, Types.DECIMAL)
                .addValue("category", category, Types.VARCHAR);
    }
}
