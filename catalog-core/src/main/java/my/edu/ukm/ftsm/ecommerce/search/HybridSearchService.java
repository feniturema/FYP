package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.search.KeywordRecall.ItemRow;
import my.edu.ukm.ftsm.ecommerce.search.KeywordRecall.ProductRow;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery.Type;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchDebug;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchHit;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.Usage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue search behind {@code GET /api/search} (docs/phases/P5a.md §6.3, §6.4). P5a implements {@link Mode#KEYWORD}
 * only; P5b adds the vector, RRF and rerank modes behind the same signature.
 */
@Service
public class HybridSearchService {

    public enum Mode { KEYWORD, VECTOR, RRF, RRF_RERANK }

    static final String PRODUCT = "product";
    static final String ITEM = "item";

    /** One recalled row with its per-table normalised score. */
    record Candidate(String type, long id, double raw, double normalised) {
        String ref() {
            return type + ":" + id;
        }
    }

    /** normalised desc → raw desc → product before item → id asc (§6.3). */
    static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::normalised).reversed()
            .thenComparing(Comparator.comparingDouble(Candidate::raw).reversed())
            .thenComparing(c -> PRODUCT.equals(c.type()) ? 0 : 1)
            .thenComparingLong(Candidate::id);

    private final KeywordRecall recall;
    private final ProductRepository products;
    private final ItemRepository items;

    public HybridSearchService(KeywordRecall recall, ProductRepository products, ItemRepository items) {
        this.recall = recall;
        this.products = products;
        this.items = items;
    }

    public SearchResult search(SearchQuery query) {
        if (query.mode() != Mode.KEYWORD) {
            throw new UnsupportedOperationException("mode not available: " + query.mode());
        }
        long start = System.nanoTime();
        String q = query.q().strip();
        String category = query.category() == null ? null : query.category().label();

        List<Candidate> candidates = new ArrayList<>();
        if (query.type() != Type.ITEM) {
            List<ProductRow> rows = recall.products(q, query.maxPrice(), category);
            candidates.addAll(normalise(PRODUCT, rows, ProductRow::id, ProductRow::score));
        }
        if (query.type() != Type.PRODUCT) {
            List<ItemRow> rows = recall.items(q, query.maxPrice(), category);
            candidates.addAll(normalise(ITEM, rows, ItemRow::id, ItemRow::score));
        }
        List<Candidate> top = candidates.stream().sorted(ORDER).limit(query.limit()).toList();

        List<SearchHit> hits = toHits(top);
        SearchDebug debug = null;
        if (query.debug()) {
            Map<String, Map<String, Integer>> ranks = new LinkedHashMap<>();
            for (int i = 0; i < top.size(); i++) {
                ranks.put(top.get(i).ref(), Map.of("keyword", i + 1));
            }
            debug = new SearchDebug(ranks, Usage.NONE, (System.nanoTime() - start) / 1_000_000);
        }
        return new SearchResult(query.q(), Mode.KEYWORD, hits, debug);
    }

    /** Divides each score by the arm's best score: the two tables' FULLTEXT scores are not comparable. */
    static <R> List<Candidate> normalise(String type, List<R> rows, Function<R, Long> id, Function<R, Double> score) {
        double max = rows.stream().mapToDouble(score::apply).max().orElse(0);
        return rows.stream()
                .map(r -> new Candidate(type, id.apply(r), score.apply(r),
                        max > 0 ? score.apply(r) / max : 1.0))
                .toList();
    }

    private List<SearchHit> toHits(List<Candidate> top) {
        Map<Long, Product> ps = products.findAllById(idsOf(top, PRODUCT)).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Item> is = items.findAllById(idsOf(top, ITEM)).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<SearchHit> hits = new ArrayList<>(top.size());
        for (Candidate c : top) {
            if (PRODUCT.equals(c.type())) {
                Product p = ps.get(c.id());
                if (p != null) {    // deleted between recall and load
                    hits.add(new SearchHit(c.ref(), PRODUCT, p.getId(), p.getName(), p.getPrice(), p.getCategory(),
                            p.getImageUrl(), c.normalised()));
                }
            } else {
                Item it = is.get(c.id());
                if (it != null) {
                    hits.add(new SearchHit(c.ref(), ITEM, it.getId(), it.getTitle(), it.getPrice(), it.getCategory(),
                            it.getImageUrl(), c.normalised()));
                }
            }
        }
        return hits;
    }

    private static List<Long> idsOf(List<Candidate> cs, String type) {
        return cs.stream().filter(c -> type.equals(c.type())).map(Candidate::id).toList();
    }
}
