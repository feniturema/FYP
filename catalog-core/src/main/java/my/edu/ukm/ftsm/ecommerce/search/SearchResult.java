package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService.Mode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Result of {@link HybridSearchService#search(SearchQuery)} (docs/phases/P5a.md §6.4). */
public record SearchResult(String query, Mode effectiveMode, List<SearchHit> hits, SearchDebug debug) {

    /** {@code ref} is "product:1001" / "item:5001"; {@code score} is the per-table normalised score in (0, 1]. */
    public record SearchHit(String ref, String type, long id, String title, BigDecimal price, String category,
                            String imageUrl, double score) {}

    /** {@code ranks}: ref → {retriever → 1-based rank}. Only present when the request asked for debug. */
    public record SearchDebug(Map<String, Map<String, Integer>> ranks, Usage usage, long latencyMs) {}

    /** Token usage of model calls made for this search (all zero in KEYWORD mode). */
    public record Usage(long embeddingTokens, long rerankInputTokens, long rerankOutputTokens, boolean rerankFallback) {
        public static final Usage NONE = new Usage(0, 0, 0, false);
    }
}
