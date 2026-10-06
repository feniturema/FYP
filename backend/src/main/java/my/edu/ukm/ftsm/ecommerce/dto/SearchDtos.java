package my.edu.ukm.ftsm.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchDebug;
import my.edu.ukm.ftsm.ecommerce.search.SearchResult.SearchHit;

import java.util.List;

/** JSON shape of {@code GET /api/search} (docs/phases/P5a.md §6.4). */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** {@code debug} is omitted unless the request asked for it (dev/test profiles only). */
    public record SearchResponse(String query, String effectiveMode, List<SearchHit> hits,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) SearchDebug debug) {
        public static SearchResponse from(SearchResult r) {
            return new SearchResponse(r.query(), r.effectiveMode().name(), r.hits(), r.debug());
        }
    }
}
