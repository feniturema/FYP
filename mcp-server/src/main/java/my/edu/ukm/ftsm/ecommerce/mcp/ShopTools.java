package my.edu.ukm.ftsm.ecommerce.mcp;

import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.Review;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ReviewRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.search.CatalogCategory;
import my.edu.ukm.ftsm.ecommerce.search.CatalogSearchService;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.FlashSaleView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ItemView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ProductDetailView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ProductView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.StockView;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The five read-only catalogue tools (docs/phases/P4b.md §6.5). Invalid arguments throw
 * {@link IllegalArgumentException}; Spring AI returns that to the model as a tool error.
 */
@Component
public class ShopTools {

    static final int MAX_RESULTS = 10;

    private final CatalogSearchService search;
    private final ProductRepository products;
    private final SeckillEventRepository events;
    private final ReviewRepository reviews;
    private final StringRedisTemplate redis;

    public ShopTools(CatalogSearchService search, ProductRepository products, SeckillEventRepository events,
                     ReviewRepository reviews, StringRedisTemplate redis) {
        this.search = search;
        this.products = products;
        this.events = events;
        this.reviews = reviews;
        this.redis = redis;
    }

    @Tool(name = "search_products", description = "Search the official FTSM store (new B2C products) by keyword. "
            + "Use it whenever the user looks for a product, asks what is sold, or gives a budget or category. "
            + "Returns at most 10 products with price (RM) and stock.")
    public Object searchProducts(
            @ToolParam(description = "Search keyword, 1-200 characters, e.g. 'hoodie'") String keyword,
            @ToolParam(required = false, description = "Maximum price in RM, >= 0") BigDecimal maxPrice,
            @ToolParam(required = false, description = "Exact category name, e.g. 'Apparel'") String category) {
        requireKeyword(keyword);
        requireNonNegative(maxPrice);
        List<ProductView> found = search.searchProducts(keyword, maxPrice, category, MAX_RESULTS);
        if (category != null && !category.isBlank() && CatalogCategory.parse(category).isEmpty()) {
            // P5a §6.5: an unknown category is not an error; search without it and say so.
            return new CategoryIgnored("Unknown category '" + category.strip() + "' was ignored; categories are: "
                    + CatalogCategory.allowedLabels() + ".", found);
        }
        return found;
    }

    /** search_products result when the category was not one of {@link CatalogCategory}. */
    public record CategoryIgnored(String note, List<ProductView> products) {}

    @Tool(name = "search_secondhand_items", description = "Search second-hand items listed by students (C2C) by keyword. "
            + "Use it when the user asks for used or second-hand goods. Only items still for sale are returned, at most 10.")
    public List<ItemView> searchSecondhandItems(
            @ToolParam(description = "Search keyword, 1-200 characters") String keyword,
            @ToolParam(required = false, description = "Maximum price in RM, >= 0") BigDecimal maxPrice) {
        requireKeyword(keyword);
        requireNonNegative(maxPrice);
        return search.searchItems(keyword, maxPrice, MAX_RESULTS);
    }

    @Tool(name = "get_product_detail", description = "Get the description, average rating and review count of one "
            + "store product. Use it after search_products when the user asks about a specific product. "
            + "Returns {\"found\":false} when the id does not exist.")
    public ProductDetailView getProductDetail(@ToolParam(description = "Product id from search_products") Long productId) {
        requireId(productId);
        return products.findById(productId)
                .map(p -> {
                    List<Review> rs = reviews.findByTargetTypeAndTargetRefId(Review.TargetType.PRODUCT, p.getId());
                    double avg = rs.stream().mapToInt(Review::getRating).average().orElse(0.0);
                    return new ProductDetailView(true, ProductView.from(p), p.getDescription(), avg, rs.size());
                })
                .orElseGet(ProductDetailView::notFound);
    }

    @Tool(name = "get_stock", description = "Get the current stock of one store product, and the live flash sale for it "
            + "(SecKill price, remaining units, end time) if one is running. Use it for any 'is it in stock' or "
            + "'how many are left' question.")
    public StockView getStock(@ToolParam(description = "Product id from search_products") Long productId) {
        requireId(productId);
        Product p = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("no product with id " + productId));
        StockView.FlashSaleStock flashSale = events.findByProductIdAndStatus(productId, SeckillEvent.Status.ACTIVE).stream()
                .min(Comparator.comparing(SeckillEvent::getEndTime))
                .map(e -> new StockView.FlashSaleStock(e.getId(), e.getSeckillPrice(), remaining(e.getId()), e.getEndTime()))
                .orElse(null);
        return new StockView(p.getId(), p.getTotalStock() == null ? 0 : p.getTotalStock(), flashSale);
    }

    @Tool(name = "list_flash_sales", description = "List flash sales (SecKill events). Use it when the user asks about "
            + "flash sales, deals or upcoming sales. status: PENDING (not started) or ACTIVE (running); omit for both.")
    public List<FlashSaleView> listFlashSales(
            @ToolParam(required = false, description = "PENDING or ACTIVE; omit for both") String status) {
        List<SeckillEvent> list;
        if (status == null || status.isBlank()) {
            list = new ArrayList<>(events.findByStatus(SeckillEvent.Status.ACTIVE));
            list.addAll(events.findByStatus(SeckillEvent.Status.PENDING));
        } else {
            String s = status.strip().toUpperCase(Locale.ROOT);
            if (!s.equals("PENDING") && !s.equals("ACTIVE")) {
                throw new IllegalArgumentException("status must be PENDING or ACTIVE");
            }
            list = events.findByStatus(SeckillEvent.Status.valueOf(s));
        }
        Map<Long, String> names = products.findAllById(list.stream().map(SeckillEvent::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getName, (a, b) -> a));
        return list.stream()
                .sorted(Comparator.comparing(SeckillEvent::getStartTime))
                .map(e -> FlashSaleView.from(e, names.getOrDefault(e.getProductId(), "(deleted product)")))
                .toList();
    }

    /** Remaining SecKill units from Redis; null when the stock key does not exist (not warmed or cleaned up). */
    private Integer remaining(Long eventId) {
        String v = redis.opsForValue().get(RedisKeys.seckillStock(eventId));
        return v == null ? null : Integer.valueOf(v);
    }

    private static void requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 200) {
            throw new IllegalArgumentException("keyword must be 1-200 characters");
        }
    }

    private static void requireNonNegative(BigDecimal maxPrice) {
        if (maxPrice != null && maxPrice.signum() < 0) {
            throw new IllegalArgumentException("maxPrice must be >= 0");
        }
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("productId must be a positive integer");
        }
    }
}
