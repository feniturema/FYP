package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ItemView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ProductView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keyword search for the MCP tools (docs/phases/P4b.md §3, P5a §6.5). Since P5a it uses the FULLTEXT
 * {@link KeywordRecall} with the §6.3 order (relevance desc, id asc); the signatures are unchanged. An unknown
 * {@code category} means "no category filter" (the caller decides whether to tell the user).
 */
@Service
public class CatalogSearchService {

    private final KeywordRecall recall;
    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;

    public CatalogSearchService(KeywordRecall recall, ProductRepository productRepository,
                                ItemRepository itemRepository) {
        this.recall = recall;
        this.productRepository = productRepository;
        this.itemRepository = itemRepository;
    }

    /** @param maxPrice and category are optional (null = no filter); limit must be ≥ 1 */
    public List<ProductView> searchProducts(String keyword, BigDecimal maxPrice, String category, int limit) {
        requireKeyword(keyword);
        String label = CatalogCategory.parse(category).map(CatalogCategory::label).orElse(null);
        List<Long> ids = recall.products(keyword.strip(), maxPrice, label).stream()
                .limit(Math.max(1, limit)).map(KeywordRecall.ProductRow::id).toList();
        Map<Long, ProductView> byId = productRepository.findAllById(ids).stream()
                .map(ProductView::from).collect(Collectors.toMap(ProductView::id, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** Only ACTIVE second-hand items. */
    public List<ItemView> searchItems(String keyword, BigDecimal maxPrice, int limit) {
        requireKeyword(keyword);
        List<Long> ids = recall.items(keyword.strip(), maxPrice, null).stream()
                .limit(Math.max(1, limit)).map(KeywordRecall.ItemRow::id).toList();
        Map<Long, ItemView> byId = itemRepository.findAllById(ids).stream()
                .map(ItemView::from).collect(Collectors.toMap(ItemView::id, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static void requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 200) {
            throw new IllegalArgumentException("keyword must be 1-200 characters");
        }
    }
}
