package my.edu.ukm.ftsm.ecommerce.search;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService.Mode;
import my.edu.ukm.ftsm.ecommerce.search.KeywordRecall.ItemRow;
import my.edu.ukm.ftsm.ecommerce.search.KeywordRecall.ProductRow;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery.Type;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/phases/P5a.md §6.3 / §8: normalisation, merge order and its four tie-breaks, type and limit. */
class HybridSearchServiceTest {

    private final KeywordRecall recall = mock(KeywordRecall.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ItemRepository items = mock(ItemRepository.class);
    private final HybridSearchService service = new HybridSearchService(recall, products, items);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void entitiesExistForEveryId() {
        when(products.findAllById(any())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                .map(id -> Product.builder().id(id).name("p" + id).price(new BigDecimal("10.00")).totalStock(1)
                        .category("Apparel").build()).toList());
        when(items.findAllById(any())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                .map(id -> Item.builder().id(id).sellerId(1L).title("i" + id).price(new BigDecimal("5.00"))
                        .category("Books").build()).toList());
    }

    private static SearchQuery query(Type type, int limit, boolean debug) {
        return new SearchQuery("hoodie", null, null, type, limit, Mode.KEYWORD, debug);
    }

    private static List<String> refs(SearchResult r) {
        return r.hits().stream().map(SearchHit::ref).toList();
    }

    @Test
    void scoresAreNormalisedPerTableBeforeMerging() {
        when(recall.products("hoodie", null, null)).thenReturn(List.of(new ProductRow(10, 8.0), new ProductRow(11, 4.0)));
        when(recall.items("hoodie", null, null)).thenReturn(List.of(new ItemRow(20, 2.0), new ItemRow(21, 1.0)));

        SearchResult r = service.search(query(Type.ALL, 10, false));

        // item 20's raw 2.0 is far below product 11's 4.0, but it is the best item: normalised 1.0
        assertThat(refs(r)).containsExactly("product:10", "item:20", "product:11", "item:21");
        assertThat(r.hits()).extracting(SearchHit::score).containsExactly(1.0, 1.0, 0.5, 0.5);
        assertThat(r.effectiveMode()).isEqualTo(Mode.KEYWORD);
        assertThat(r.debug()).isNull();
    }

    @Test
    void tieBreaksAreNormalisedThenRawThenProductFirstThenId() {
        // every row normalises to 1.0 or 0.5, so raw score and id decide within each group
        when(recall.products("hoodie", null, null)).thenReturn(List.of(
                new ProductRow(31, 6.0), new ProductRow(30, 6.0), new ProductRow(40, 3.0)));
        when(recall.items("hoodie", null, null)).thenReturn(List.of(
                new ItemRow(3, 9.0), new ItemRow(4, 4.5)));

        SearchResult r = service.search(query(Type.ALL, 10, false));

        assertThat(refs(r)).containsExactly(
                "item:3",        // normalised 1.0, raw 9.0 beats raw 6.0
                "product:30",    // normalised 1.0, raw 6.0, both products: id 30 before 31
                "product:31",
                "item:4",        // normalised 0.5, raw 4.5 beats product 40's raw 3.0
                "product:40");
    }

    @Test
    void productComesBeforeItemOnEqualNormalisedAndRawScores() {
        when(recall.products("hoodie", null, null)).thenReturn(List.of(new ProductRow(900, 5.0)));
        when(recall.items("hoodie", null, null)).thenReturn(List.of(new ItemRow(1, 5.0)));

        assertThat(refs(service.search(query(Type.ALL, 10, false)))).containsExactly("product:900", "item:1");
    }

    @Test
    void typeItemQueriesOnlyItems() {
        when(recall.items("hoodie", null, null)).thenReturn(List.of(new ItemRow(20, 2.0)));

        SearchResult r = service.search(query(Type.ITEM, 10, false));

        assertThat(refs(r)).containsExactly("item:20");
        verify(recall, never()).products(anyString(), any(), any());
    }

    @Test
    void limitCutsTheMergedList() {
        when(recall.products("hoodie", null, null)).thenReturn(List.of(new ProductRow(1, 3.0), new ProductRow(2, 2.0)));
        when(recall.items("hoodie", null, null)).thenReturn(List.of(new ItemRow(5, 3.0), new ItemRow(6, 1.0)));

        assertThat(refs(service.search(query(Type.ALL, 3, false))))
                .containsExactly("product:1", "item:5", "product:2");
    }

    @Test
    void filtersArePassedToRecallAsTheStoredLabel() {
        service.search(new SearchQuery(" hoodie ", new BigDecimal("50"), CatalogCategory.APPAREL, Type.PRODUCT, 5,
                Mode.KEYWORD, false));
        verify(recall).products("hoodie", new BigDecimal("50"), "Apparel");
    }

    @Test
    void debugCarriesKeywordRanksAndZeroUsage() {
        when(recall.products("hoodie", null, null)).thenReturn(List.of(new ProductRow(10, 8.0)));
        when(recall.items("hoodie", null, null)).thenReturn(List.of(new ItemRow(20, 2.0)));

        SearchResult r = service.search(query(Type.ALL, 10, true));

        assertThat(r.debug().ranks()).containsEntry("product:10", java.util.Map.of("keyword", 1))
                .containsEntry("item:20", java.util.Map.of("keyword", 2));
        assertThat(r.debug().usage()).isEqualTo(SearchResult.Usage.NONE);
    }

    @Test
    void otherModesAreNotAvailableInP5a() {
        assertThatThrownBy(() -> service.search(new SearchQuery("x", null, null, Type.ALL, 1, Mode.VECTOR, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void categoryParseIgnoresCaseAndRejectsUnknown() {
        assertThat(CatalogCategory.parse("apparel")).contains(CatalogCategory.APPAREL);
        assertThat(CatalogCategory.parse(" ELECTRONICS ")).contains(CatalogCategory.ELECTRONICS);
        assertThat(CatalogCategory.parse("Gadgets")).isEmpty();
        assertThat(CatalogCategory.parse(null)).isEmpty();
        assertThat(CatalogCategory.values()).hasSize(10);
    }
}
