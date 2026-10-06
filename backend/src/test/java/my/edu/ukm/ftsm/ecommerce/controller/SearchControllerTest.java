package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.config.CorsConfig;
import my.edu.ukm.ftsm.ecommerce.config.SecurityConfig;
import my.edu.ukm.ftsm.ecommerce.search.CatalogCategory;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService;
import my.edu.ukm.ftsm.ecommerce.search.HybridSearchService.Mode;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery;
import my.edu.ukm.ftsm.ecommerce.search.SearchQuery.Type;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchDebug;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchHit;
import my.edu.ukm.ftsm.ecommerce.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of GET /api/search (docs/phases/P5a.md §6.4, §8) with the real security chain, anonymous. */
class SearchControllerTest {

    private static final SearchHit HIT = new SearchHit("product:1001", "product", 1001, "FTSM Hoodie",
            new BigDecimal("79.00"), "Apparel", null, 1.0);

    abstract static class Base {
        @Autowired
        MockMvc mvc;
        @MockitoBean
        HybridSearchService search;

        @BeforeEach
        void answer() {
            when(search.search(any())).thenAnswer(inv -> {
                SearchQuery q = inv.getArgument(0);
                SearchDebug debug = q.debug() ? new SearchDebug(Map.of(HIT.ref(), Map.of("keyword", 1)),
                        SearchResult.Usage.NONE, 3) : null;
                return new SearchResult(q.q(), Mode.KEYWORD, List.of(HIT), debug);
            });
        }

        SearchQuery sent() {
            ArgumentCaptor<SearchQuery> c = ArgumentCaptor.forClass(SearchQuery.class);
            verify(search).search(c.capture());
            return c.getValue();
        }

        void rejects(String param, String value, String message) throws Exception {
            mvc.perform(get("/api/search").param("q", "hoodie").param(param, value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(message)));
            verifyNoInteractions(search);
        }
    }

    /** application.yml defaults to the dev profile, so the production behaviour needs prod explicitly. */
    @Nested
    @WebMvcTest(SearchController.class)
    @Import({SecurityConfig.class, CorsConfig.class, JwtUtils.class})
    @ActiveProfiles("prod")
    class ProdProfile extends Base {

        @Test
        void anonymousSearchIs200WithHitsAndNoDebug() throws Exception {
            mvc.perform(get("/api/search").param("q", " hoodie "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.query").value("hoodie"))
                    .andExpect(jsonPath("$.effectiveMode").value("KEYWORD"))
                    .andExpect(jsonPath("$.hits[0].ref").value("product:1001"))
                    .andExpect(jsonPath("$.hits[0].price").value(79.00))
                    .andExpect(jsonPath("$.debug").doesNotExist());
            SearchQuery q = sent();
            assertThat(q.type()).isEqualTo(Type.ALL);
            assertThat(q.limit()).isEqualTo(10);
            assertThat(q.maxPrice()).isNull();
            assertThat(q.category()).isNull();
        }

        @Test
        void qMustBe1To200CharactersAfterTrim() throws Exception {
            for (String q : new String[] {"", "   ", "x".repeat(201)}) {
                mvc.perform(get("/api/search").param("q", q))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("q must be 1-200 characters"));
            }
            mvc.perform(get("/api/search")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/search").param("q", "x".repeat(200))).andExpect(status().isOk());
        }

        @Test
        void maxPriceBounds() throws Exception {
            rejects("maxPrice", "-1", "maxPrice");
            rejects("maxPrice", "1.234", "maxPrice");
            rejects("maxPrice", "1000000.01", "maxPrice");
            rejects("maxPrice", "abc", "maxPrice");
        }

        @Test
        void maxPriceEdgesAreAccepted() throws Exception {
            mvc.perform(get("/api/search").param("q", "hoodie").param("maxPrice", "1000000.00"))
                    .andExpect(status().isOk());
            assertThat(sent().maxPrice()).isEqualByComparingTo("1000000");
        }

        @Test
        void unknownCategoryListsTheAllowedValues() throws Exception {
            rejects("category", "Gadgets", "Apparel, Accessories");
        }

        @Test
        void categoryIsCaseInsensitive() throws Exception {
            mvc.perform(get("/api/search").param("q", "hoodie").param("category", "apparel"))
                    .andExpect(status().isOk());
            assertThat(sent().category()).isEqualTo(CatalogCategory.APPAREL);
        }

        @Test
        void limitBounds() throws Exception {
            rejects("limit", "0", "limit");
            rejects("limit", "21", "limit");
        }

        @Test
        void typeMustBeProductItemOrAll() throws Exception {
            rejects("type", "service", "type");
        }

        @Test
        void typeAndLimitArePassedThrough() throws Exception {
            mvc.perform(get("/api/search").param("q", "hoodie").param("type", "ITEM").param("limit", "20"))
                    .andExpect(status().isOk());
            SearchQuery q = sent();
            assertThat(q.type()).isEqualTo(Type.ITEM);
            assertThat(q.limit()).isEqualTo(20);
        }

        @Test
        void modeAndDebugAreIgnoredOutsideDevAndTest() throws Exception {
            mvc.perform(get("/api/search").param("q", "hoodie").param("mode", "vector").param("debug", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.debug").doesNotExist());
            SearchQuery q = sent();
            assertThat(q.mode()).isEqualTo(Mode.KEYWORD);
            assertThat(q.debug()).isFalse();
        }
    }

    @Nested
    @WebMvcTest(SearchController.class)
    @Import({SecurityConfig.class, CorsConfig.class, JwtUtils.class})
    @ActiveProfiles("dev")
    class DevProfile extends Base {

        @Test
        void otherModesAre400() throws Exception {
            for (String mode : new String[] {"vector", "rrf", "rrf_rerank"}) {
                mvc.perform(get("/api/search").param("q", "hoodie").param("mode", mode))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("mode not available"));
            }
            verifyNoInteractions(search);
        }

        @Test
        void keywordModeWithDebugReturnsRanksAndUsage() throws Exception {
            mvc.perform(get("/api/search").param("q", "hoodie").param("mode", "keyword").param("debug", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.debug.ranks['product:1001'].keyword").value(1))
                    .andExpect(jsonPath("$.debug.usage.embeddingTokens").value(0))
                    .andExpect(jsonPath("$.debug.latencyMs").exists());
            assertThat(sent().debug()).isTrue();
        }
    }
}
