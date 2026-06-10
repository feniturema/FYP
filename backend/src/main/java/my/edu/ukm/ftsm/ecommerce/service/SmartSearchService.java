package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.ItemDtos.ItemResponse;
import my.edu.ukm.ftsm.ecommerce.dto.ProductDtos.ProductResponse;
import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.service.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Intent-aware "smart search": instead of a SQL LIKE keyword match, prefilter a
 * candidate set and let the LLM rerank by the user's natural-language intent
 * (e.g. "cheap dorm fan"). No vector store — keyword prefilter + LLM rerank.
 * Always degrades to the plain keyword result if the LLM is unavailable.
 */
@Service
public class SmartSearchService {

    private static final Logger log = LoggerFactory.getLogger(SmartSearchService.class);
    private static final int MAX_CANDIDATES = 50;

    private static final String RERANK_PERSONA = """
            You are a search ranker for a campus marketplace. Given a shopper's query and a
            list of candidate items (id + attributes), return ONLY the relevant ids ordered
            by how well they match the shopper's intent (best first). Drop clearly irrelevant
            items. Respond with strict JSON: {"ids":[<id>,<id>,...]} and nothing else.
            """;

    private final ItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public SmartSearchService(ItemRepository itemRepository, ProductRepository productRepository,
                              LlmClient llm, ObjectMapper mapper) {
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.llm = llm;
        this.mapper = mapper;
    }

    public List<ItemResponse> smartSearchItems(String query) {
        List<Item> candidates = candidateItems(query);
        List<Item> ranked = rerank(query, candidates, Item::getId, this::describeItem);
        return ranked.stream().map(ItemResponse::from).toList();
    }

    public List<ProductResponse> smartSearchProducts(String query) {
        List<Product> candidates = candidateProducts(query);
        List<Product> ranked = rerank(query, candidates, Product::getId, this::describeProduct);
        return ranked.stream().map(ProductResponse::from).toList();
    }

    // ---------- candidate prefilter ----------

    private List<Item> candidateItems(String query) {
        List<Item> active = itemRepository.findByStatus(Item.Status.ACTIVE);
        if (active.size() <= MAX_CANDIDATES || query == null || query.isBlank()) {
            return active.stream().limit(MAX_CANDIDATES).toList();
        }
        // Large catalog: narrow with a keyword prefilter first.
        List<Item> narrowed = itemRepository.findByStatusAndTitleContainingIgnoreCase(Item.Status.ACTIVE, query);
        return (narrowed.isEmpty() ? active : narrowed).stream().limit(MAX_CANDIDATES).toList();
    }

    private List<Product> candidateProducts(String query) {
        List<Product> all = productRepository.findAll();
        if (all.size() <= MAX_CANDIDATES || query == null || query.isBlank()) {
            return all.stream().limit(MAX_CANDIDATES).toList();
        }
        List<Product> narrowed = productRepository.findByNameContainingIgnoreCase(query);
        return (narrowed.isEmpty() ? all : narrowed).stream().limit(MAX_CANDIDATES).toList();
    }

    // ---------- generic LLM rerank ----------

    private <T> List<T> rerank(String query, List<T> candidates,
                               Function<T, Long> idOf, Function<T, String> describe) {
        if (candidates.isEmpty() || query == null || query.isBlank() || !llm.isConfigured()) {
            return candidates;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (T c : candidates) {
                sb.append(idOf.apply(c)).append(": ").append(describe.apply(c)).append('\n');
            }
            String user = "Shopper query: " + query + "\n\nCandidates:\n" + sb;

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", RERANK_PERSONA),
                    Map.of("role", "user", "content", user));

            JsonNode resp = llm.chatCompletion(messages, null);
            if (resp == null) {
                return candidates;
            }
            String content = resp.path("choices").path(0).path("message").path("content").asText("");
            List<Long> order = parseIds(content);
            if (order.isEmpty()) {
                return candidates;
            }

            Map<Long, T> byId = candidates.stream()
                    .collect(Collectors.toMap(idOf, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            List<T> ranked = new ArrayList<>();
            for (Long id : order) {
                T match = byId.get(id);
                if (match != null) {
                    ranked.add(match);
                }
            }
            return ranked.isEmpty() ? candidates : ranked;
        } catch (Exception e) {
            log.error("[SmartSearch] rerank failed, returning keyword results: {}", e.getMessage());
            return candidates;
        }
    }

    /** Tolerant parse: accepts {"ids":[...]}, a bare [...] array, or fenced JSON. */
    private List<Long> parseIds(String content) {
        List<Long> ids = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return ids;
        }
        try {
            String cleaned = content.replaceAll("```json", "").replace("```", "").trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode arr = root.isArray() ? root : root.path("ids");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isNumber()) {
                        ids.add(n.asLong());
                    } else if (n.isObject() && n.has("id")) {
                        ids.add(n.path("id").asLong());
                    }
                }
            }
        } catch (Exception ignored) {
            // unparseable -> empty -> caller falls back to keyword order
        }
        return ids;
    }

    private String describeItem(Item i) {
        return "title=" + i.getTitle()
                + "; category=" + nz(i.getCategory())
                + "; condition=" + nz(i.getCondition())
                + "; price=RM" + i.getPrice();
    }

    private String describeProduct(Product p) {
        return "name=" + p.getName()
                + "; category=" + nz(p.getCategory())
                + "; price=RM" + p.getPrice()
                + "; stock=" + p.getTotalStock();
    }

    private String nz(String s) {
        return s == null ? "-" : s;
    }
}
