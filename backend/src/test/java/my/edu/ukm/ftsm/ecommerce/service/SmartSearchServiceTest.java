package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import my.edu.ukm.ftsm.ecommerce.dto.ItemDtos.ItemResponse;
import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.service.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SmartSearchServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final LlmClient llm = mock(LlmClient.class);

    private final SmartSearchService service =
            new SmartSearchService(itemRepository, productRepository, llm, mapper);

    private Item item(long id, String title) {
        return Item.builder().id(id).sellerId(1L).title(title)
                .price(new BigDecimal("10.00")).status(Item.Status.ACTIVE).build();
    }

    /** LLM returns an explicit order -> results are reordered to match. */
    @Test
    void reordersItemsByLlmRanking() {
        when(itemRepository.findByStatus(Item.Status.ACTIVE))
                .thenReturn(List.of(item(1, "Fan"), item(2, "Lamp"), item(3, "Mini USB Fan")));
        when(llm.isConfigured()).thenReturn(true);
        when(llm.chatCompletion(any(), isNull())).thenReturn(llmReply("{\"ids\":[3,1]}"));

        List<ItemResponse> out = service.smartSearchItems("cheap dorm fan");

        assertThat(out).extracting(ItemResponse::id).containsExactly(3L, 1L);
    }

    /** When the LLM is down, smart search degrades to the keyword candidate list. */
    @Test
    void fallsBackToKeywordResultsWhenLlmUnavailable() {
        when(itemRepository.findByStatus(Item.Status.ACTIVE))
                .thenReturn(List.of(item(1, "Fan"), item(2, "Lamp")));
        when(llm.isConfigured()).thenReturn(false);

        List<ItemResponse> out = service.smartSearchItems("fan");

        assertThat(out).extracting(ItemResponse::id).containsExactly(1L, 2L);
        verify(llm, never()).chatCompletion(any(), any());
    }

    private com.fasterxml.jackson.databind.JsonNode llmReply(String content) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode message = root.putArray("choices").addObject().putObject("message");
        message.put("content", content);
        return root;
    }
}
