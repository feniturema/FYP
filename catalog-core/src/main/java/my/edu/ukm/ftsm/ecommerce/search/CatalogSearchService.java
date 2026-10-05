package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ItemView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ProductView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Keyword search over the catalogue (docs/phases/P4b.md §3). P4b keeps the existing LIKE lookups
 * ({@code findByNameContainingIgnoreCase}, {@code findByStatusAndTitleContainingIgnoreCase}) and filters by price and
 * category in memory; P5a replaces the implementation with FULLTEXT and keeps these signatures.
 */
@Service
public class CatalogSearchService {

    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;

    public CatalogSearchService(ProductRepository productRepository, ItemRepository itemRepository) {
        this.productRepository = productRepository;
        this.itemRepository = itemRepository;
    }

    /** @param maxPrice and category are optional (null = no filter); limit must be ≥ 1 */
    public List<ProductView> searchProducts(String keyword, BigDecimal maxPrice, String category, int limit) {
        requireKeyword(keyword);
        return productRepository.findByNameContainingIgnoreCase(keyword.strip()).stream()
                .filter(p -> maxPrice == null || (p.getPrice() != null && p.getPrice().compareTo(maxPrice) <= 0))
                .filter(p -> category == null || category.isBlank() || category.equalsIgnoreCase(p.getCategory()))
                .limit(Math.max(1, limit))
                .map(ProductView::from)
                .toList();
    }

    /** Only ACTIVE second-hand items. */
    public List<ItemView> searchItems(String keyword, BigDecimal maxPrice, int limit) {
        requireKeyword(keyword);
        return itemRepository.findByStatusAndTitleContainingIgnoreCase(Item.Status.ACTIVE, keyword.strip()).stream()
                .filter(i -> maxPrice == null || (i.getPrice() != null && i.getPrice().compareTo(maxPrice) <= 0))
                .limit(Math.max(1, limit))
                .map(ItemView::from)
                .toList();
    }

    private static void requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 200) {
            throw new IllegalArgumentException("keyword must be 1-200 characters");
        }
    }
}
