package my.edu.ukm.ftsm.ecommerce.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.ChatDtos.ActionCard;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.service.SeckillService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final SeckillService seckillService = mock(SeckillService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final ChatTools tools = new ChatTools(
            productRepository, itemRepository, seckillService, orderRepository, redis, mapper);

    @Test
    void searchProductsReturnsJsonAndAccumulatesActionCards() {
        Product p = Product.builder().id(7L).name("Limited Lanyard")
                .price(new BigDecimal("15.00")).totalStock(50).category("Merch").build();
        when(productRepository.findByNameContainingIgnoreCase("lanyard")).thenReturn(List.of(p));

        List<ActionCard> actions = new ArrayList<>();
        String json = tools.execute("search_products", "{\"keyword\":\"lanyard\"}", 1L, actions);

        assertThat(json).contains("Limited Lanyard").contains("\"id\":7");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).sourceType()).isEqualTo("B2C_PRODUCT");
        assertThat(actions.get(0).refId()).isEqualTo(7L);
    }

    @Test
    void getMyOrdersRequiresLogin() {
        String json = tools.execute("get_my_orders", "{}", null, new ArrayList<>());
        assertThat(json).contains("not logged in");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void unknownToolIsHandledGracefully() {
        String json = tools.execute("does_not_exist", "{}", 1L, new ArrayList<>());
        assertThat(json).contains("unknown tool");
    }
}
