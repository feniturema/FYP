package my.edu.ukm.ftsm.ecommerce.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ActionCard;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillEventResponse;
import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.service.SeckillService;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backend tools the AI shopping assistant can call (OpenAI function-calling).
 * Each tool reads live platform data and returns a compact JSON string for the model;
 * product/listing tools also accumulate {@link ActionCard}s so the frontend can render
 * clickable "Add to cart" / "View" suggestions.
 */
@Component
public class ChatTools {

    private static final Logger log = LoggerFactory.getLogger(ChatTools.class);
    private static final int LIMIT = 8;

    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;
    private final SeckillService seckillService;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ChatTools(ProductRepository productRepository, ItemRepository itemRepository,
                     SeckillService seckillService, OrderRepository orderRepository,
                     StringRedisTemplate redis, ObjectMapper mapper) {
        this.productRepository = productRepository;
        this.itemRepository = itemRepository;
        this.seckillService = seckillService;
        this.orderRepository = orderRepository;
        this.redis = redis;
        this.mapper = mapper;
    }

    /** OpenAI-compatible tool specs advertised to the model. */
    public static final List<Map<String, Object>> TOOL_SPECS = List.of(
            fn("search_products",
                    "Search the official store (B2C) products by keyword. Returns id, name, price, stock, category.",
                    Map.of("keyword", strParam("Keyword to match against product names (optional; empty lists all).")),
                    List.of()),
            fn("search_items",
                    "Search second-hand student listings (C2C) by keyword and/or category. Returns id, title, price, condition, category.",
                    Map.of(
                            "keyword", strParam("Keyword to match against listing titles (optional)."),
                            "category", strParam("Exact category filter (optional).")),
                    List.of()),
            fn("get_seckill_status",
                    "List flash-sale (SecKill) events with their live remaining stock and status (PENDING/ACTIVE/ENDED).",
                    Map.of(),
                    List.of()),
            fn("get_my_orders",
                    "Look up the current logged-in user's recent orders (id, type, amount, status). Use when the user asks about their orders.",
                    Map.of(),
                    List.of())
    );

    /**
     * Dispatch a single tool call.
     *
     * @param name    tool name
     * @param argsJson raw JSON arguments string from the model
     * @param userId  current authenticated user (may be null)
     * @param actions out-param: product/listing cards accumulated for the frontend
     * @return compact JSON string result for the model
     */
    public String execute(String name, String argsJson, Long userId, List<ActionCard> actions) {
        try {
            JsonNode args = parse(argsJson);
            return switch (name) {
                case "search_products" -> searchProducts(args, actions);
                case "search_items" -> searchItems(args, actions);
                case "get_seckill_status" -> seckillStatus();
                case "get_my_orders" -> myOrders(userId);
                default -> "{\"error\":\"unknown tool: " + name + "\"}";
            };
        } catch (Exception e) {
            log.error("[ChatTools] tool '{}' failed: {}", name, e.getMessage());
            return "{\"error\":\"tool execution failed\"}";
        }
    }

    // ---------- tool implementations ----------

    private String searchProducts(JsonNode args, List<ActionCard> actions) throws Exception {
        String kw = args.path("keyword").asText("");
        List<Product> list = kw.isBlank()
                ? productRepository.findAll()
                : productRepository.findByNameContainingIgnoreCase(kw);
        list = list.stream().limit(LIMIT).toList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("price", p.getPrice());
            m.put("stock", p.getTotalStock());
            m.put("category", p.getCategory());
            out.add(m);
            actions.add(new ActionCard("B2C_PRODUCT", p.getId(), p.getName(), p.getPrice(), p.getImageUrl()));
        }
        return mapper.writeValueAsString(out);
    }

    private String searchItems(JsonNode args, List<ActionCard> actions) throws Exception {
        String kw = args.path("keyword").asText("");
        String cat = args.path("category").asText("");
        List<Item> list;
        if (!cat.isBlank()) {
            list = itemRepository.findByStatusAndCategory(Item.Status.ACTIVE, cat);
        } else if (!kw.isBlank()) {
            list = itemRepository.findByStatusAndTitleContainingIgnoreCase(Item.Status.ACTIVE, kw);
        } else {
            list = itemRepository.findByStatus(Item.Status.ACTIVE);
        }
        list = list.stream().limit(LIMIT).toList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("title", i.getTitle());
            m.put("price", i.getPrice());
            m.put("condition", i.getCondition());
            m.put("category", i.getCategory());
            out.add(m);
            actions.add(new ActionCard("C2C_ITEM", i.getId(), i.getTitle(), i.getPrice(), i.getImageUrl()));
        }
        return mapper.writeValueAsString(out);
    }

    private String seckillStatus() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SeckillEventResponse e : seckillService.listEvents()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", e.id());
            m.put("product", e.productName());
            m.put("seckillPrice", e.seckillPrice());
            m.put("status", e.status());
            m.put("liveStock", liveStock(e.id(), e.seckillStock()));
            m.put("startTime", e.startTime());
            m.put("endTime", e.endTime());
            out.add(m);
        }
        return mapper.writeValueAsString(out);
    }

    private String myOrders(Long userId) throws Exception {
        if (userId == null) {
            return "{\"error\":\"not logged in\"}";
        }
        List<Order> orders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(userId)
                .stream().limit(LIMIT).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Order o : orders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("type", o.getSourceType().name());
            m.put("amount", o.getAmount());
            m.put("status", o.getStatus().name());
            m.put("createdAt", o.getCreatedAt());
            out.add(m);
        }
        return mapper.writeValueAsString(out);
    }

    // ---------- helpers ----------

    /** Live remaining stock from Redis; falls back to the configured stock if not warmed. */
    private Object liveStock(Long eventId, Integer fallback) {
        try {
            String v = redis.opsForValue().get(RedisKeys.seckillStock(eventId));
            if (v != null) return Integer.parseInt(v);
        } catch (Exception ignored) {
            // redis unavailable — fall through
        }
        return fallback;
    }

    private JsonNode parse(String argsJson) throws Exception {
        if (argsJson == null || argsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(argsJson);
    }

    private static Map<String, Object> fn(String name, String description,
                                          Map<String, Object> properties, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", required);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", params);
        return Map.of("type", "function", "function", function);
    }

    private static Map<String, Object> strParam(String description) {
        return Map.of("type", "string", "description", description);
    }
}
