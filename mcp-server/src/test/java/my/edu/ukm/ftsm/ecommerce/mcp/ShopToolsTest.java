package my.edu.ukm.ftsm.ecommerce.mcp;

import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.Review;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ReviewRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.search.CatalogSearchService;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.FlashSaleView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ProductDetailView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.ProductView;
import my.edu.ukm.ftsm.ecommerce.search.SearchDtos.StockView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The five tools with normal input, unknown ids and invalid arguments (docs/phases/P4b.md §8). */
@SuppressWarnings("unchecked")
class ShopToolsTest {

    private final CatalogSearchService search = mock(CatalogSearchService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SeckillEventRepository events = mock(SeckillEventRepository.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private ShopTools tools;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        tools = new ShopTools(search, products, events, reviews, redis);
    }

    @Test
    void searchProductsIgnoresAnUnknownCategoryAndSaysSo() {
        ProductView hoodie = new ProductView(1, "FTSM Hoodie", new BigDecimal("79.00"), "Apparel", 7, null);
        when(search.searchProducts("hoodie", null, "Gadgets", ShopTools.MAX_RESULTS)).thenReturn(List.of(hoodie));

        Object out = tools.searchProducts("hoodie", null, "Gadgets");

        assertThat(out).isInstanceOf(ShopTools.CategoryIgnored.class);
        ShopTools.CategoryIgnored ignored = (ShopTools.CategoryIgnored) out;
        assertThat(ignored.note()).contains("Gadgets").contains("Apparel");
        assertThat(ignored.products()).containsExactly(hoodie);
        assertThat(tools.searchProducts("hoodie", null, "apparel")).isInstanceOf(List.class);
    }

    @Test
    void searchProductsDelegatesWithTheLimit() {
        tools.searchProducts("hoodie", new BigDecimal("50"), "Apparel");
        verify(search).searchProducts("hoodie", new BigDecimal("50"), "Apparel", ShopTools.MAX_RESULTS);
    }

    @Test
    void searchToolsRejectInvalidArguments() {
        assertThatThrownBy(() -> tools.searchProducts(" ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchProducts("x".repeat(201), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchProducts("hoodie", new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchSecondhandItems(null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(search);
    }

    @Test
    void searchSecondhandItemsDelegates() {
        tools.searchSecondhandItems("calculator", null);
        verify(search).searchItems("calculator", null, ShopTools.MAX_RESULTS);
    }

    @Test
    void productDetailAveragesTheReviews() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, 7)));
        when(reviews.findByTargetTypeAndTargetRefId(Review.TargetType.PRODUCT, 1L))
                .thenReturn(List.of(review(4), review(5)));

        ProductDetailView d = tools.getProductDetail(1L);

        assertThat(d.found()).isTrue();
        assertThat(d.product().name()).isEqualTo("Hoodie");
        assertThat(d.averageRating()).isEqualTo(4.5);
        assertThat(d.reviewCount()).isEqualTo(2);
    }

    @Test
    void productDetailOfAnUnknownIdIsNotFound() {
        when(products.findById(99L)).thenReturn(Optional.empty());
        assertThat(tools.getProductDetail(99L).found()).isFalse();
        assertThatThrownBy(() -> tools.getProductDetail(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stockIncludesTheLiveFlashSaleWithRedisRemaining() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, 7)));
        when(events.findByProductIdAndStatus(1L, SeckillEvent.Status.ACTIVE)).thenReturn(List.of(event(5L, 1L)));
        when(values.get("seckill:stock:{5}")).thenReturn("3");

        StockView s = tools.getStock(1L);

        assertThat(s.totalStock()).isEqualTo(7);
        assertThat(s.flashSale().eventId()).isEqualTo(5L);
        assertThat(s.flashSale().remaining()).isEqualTo(3);
    }

    @Test
    void stockWithoutFlashSaleOrRedisKey() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, 7)));
        when(events.findByProductIdAndStatus(1L, SeckillEvent.Status.ACTIVE)).thenReturn(List.of());
        assertThat(tools.getStock(1L).flashSale()).isNull();

        when(events.findByProductIdAndStatus(1L, SeckillEvent.Status.ACTIVE)).thenReturn(List.of(event(5L, 1L)));
        when(values.get("seckill:stock:{5}")).thenReturn(null);
        assertThat(tools.getStock(1L).flashSale().remaining()).isNull();
    }

    @Test
    void stockOfAnUnknownProductIsAToolError() {
        when(products.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.getStock(99L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getStock(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flashSalesByStatus() {
        when(events.findByStatus(SeckillEvent.Status.ACTIVE)).thenReturn(List.of(event(5L, 1L)));
        when(events.findByStatus(SeckillEvent.Status.PENDING)).thenReturn(List.of(event(6L, 1L)));
        when(products.findAllById(any())).thenReturn(List.of(product(1L, 7)));

        assertThat(tools.listFlashSales(null)).extracting(FlashSaleView::eventId).containsExactlyInAnyOrder(5L, 6L);
        assertThat(tools.listFlashSales("active")).extracting(FlashSaleView::productName).containsExactly("Hoodie");
        assertThatThrownBy(() -> tools.listFlashSales("ENDED")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Product product(Long id, int stock) {
        return Product.builder().id(id).name("Hoodie").price(new BigDecimal("59.90")).totalStock(stock)
                .category("Apparel").build();
    }

    private static SeckillEvent event(Long id, Long productId) {
        return SeckillEvent.builder().id(id).productId(productId).seckillPrice(new BigDecimal("39.90")).seckillStock(5)
                .startTime(Instant.now().minusSeconds(60)).endTime(Instant.now().plusSeconds(3600))
                .status(SeckillEvent.Status.ACTIVE).build();
    }

    private static Review review(int rating) {
        return Review.builder().authorId(1L).targetType(Review.TargetType.PRODUCT).targetRefId(1L).rating(rating).build();
    }
}
