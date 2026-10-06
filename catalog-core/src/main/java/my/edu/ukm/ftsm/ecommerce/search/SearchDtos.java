package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only views returned by the catalogue search and the MCP tools (docs/phases/P4b.md §6.6).
 * P5a/P5b change how they are found, not their shape.
 */
public final class SearchDtos {

    private SearchDtos() {}

    public record ProductView(long id, String name, BigDecimal price, String category, int totalStock, String imageUrl) {
        public static ProductView from(Product p) {
            return new ProductView(p.getId(), p.getName(), p.getPrice(), p.getCategory(),
                    p.getTotalStock() == null ? 0 : p.getTotalStock(), p.getImageUrl());
        }
    }

    public record ItemView(long id, String title, BigDecimal price, String category, String condition, String imageUrl) {
        public static ItemView from(Item i) {
            return new ItemView(i.getId(), i.getTitle(), i.getPrice(), i.getCategory(), i.getCondition(), i.getImageUrl());
        }
    }

    public record FlashSaleView(long eventId, long productId, String productName, BigDecimal seckillPrice, String status,
                                Instant startTime, Instant endTime) {
        public static FlashSaleView from(SeckillEvent e, String productName) {
            return new FlashSaleView(e.getId(), e.getProductId(), productName, e.getSeckillPrice(), e.getStatus().name(),
                    e.getStartTime(), e.getEndTime());
        }
    }

    public record StockView(long productId, int totalStock, FlashSaleStock flashSale) {
        /** {@code remaining} comes from Redis {@code seckill:stock:{eventId}}; null when the key does not exist. */
        public record FlashSaleStock(long eventId, BigDecimal seckillPrice, Integer remaining, Instant endsAt) {}
    }

    public record ProductDetailView(boolean found, ProductView product, String description, double averageRating,
                                    long reviewCount) {
        public static ProductDetailView notFound() {
            return new ProductDetailView(false, null, null, 0.0, 0);
        }
    }
}
