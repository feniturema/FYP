package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService.Mode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One validated search request (docs/phases/P5a.md §6.4). {@code maxPrice} and {@code category} are optional
 * (null = no filter); the controller has already checked the ranges.
 */
public record SearchQuery(String q, BigDecimal maxPrice, CatalogCategory category, Type type, int limit, Mode mode,
                          boolean debug) {

    public enum Type { PRODUCT, ITEM, ALL }

    public SearchQuery {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
    }
}
