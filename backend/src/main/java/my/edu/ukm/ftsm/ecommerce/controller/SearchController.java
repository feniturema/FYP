package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.dto.SearchDtos.SearchResponse;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.search.CatalogCategory;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService.Mode;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery.Type;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Public catalogue search (docs/phases/P5a.md §6.4). Parameters are taken as strings and validated here so every
 * rejection is a 400 with a specific {@code message}. {@code mode} and {@code debug} only take effect under the
 * {@code dev} and {@code test} profiles and are ignored elsewhere.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
    static final int MAX_LIMIT = 20;
    static final int DEFAULT_LIMIT = 10;

    private final HybridSearchService search;
    private final boolean diagnostics;

    public SearchController(HybridSearchService search, Environment env) {
        this.search = search;
        this.diagnostics = env.acceptsProfiles(Profiles.of("dev", "test"));
    }

    @GetMapping
    public SearchResponse search(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) String maxPrice,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String limit,
                                 @RequestParam(required = false) String mode,
                                 @RequestParam(required = false) String debug) {
        String query = q == null ? "" : q.strip();
        if (query.isEmpty() || query.length() > 200) {
            throw new BusinessException("q must be 1-200 characters");
        }
        SearchQuery sq = new SearchQuery(query, price(maxPrice), category(category), type(type), limit(limit),
                diagnostics ? mode(mode) : Mode.KEYWORD, diagnostics && "true".equalsIgnoreCase(debug));
        return SearchResponse.from(search.search(sq));
    }

    private static BigDecimal price(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal p = new BigDecimal(raw.strip());
            if (p.signum() >= 0 && p.compareTo(MAX_PRICE) <= 0 && p.stripTrailingZeros().scale() <= 2) {
                return p;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the 400 below
        }
        throw new BusinessException("maxPrice must be a number from 0 to 1000000 with at most 2 decimal places");
    }

    private static CatalogCategory category(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return CatalogCategory.parse(raw).orElseThrow(() -> new BusinessException(
                "category must be one of: " + CatalogCategory.allowedLabels()));
    }

    private static Type type(String raw) {
        if (raw == null || raw.isBlank()) {
            return Type.ALL;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "product" -> Type.PRODUCT;
            case "item" -> Type.ITEM;
            case "all" -> Type.ALL;
            default -> throw new BusinessException("type must be product, item or all");
        };
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int n = Integer.parseInt(raw.strip());
            if (n >= 1 && n <= MAX_LIMIT) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the 400 below
        }
        throw new BusinessException("limit must be an integer from 1 to 20");
    }

    /** P5a implements keyword only; any other value (including the P5b modes) is 400. */
    private static Mode mode(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().equalsIgnoreCase("keyword")) {
            return Mode.KEYWORD;
        }
        throw new BusinessException("mode not available");
    }
}
