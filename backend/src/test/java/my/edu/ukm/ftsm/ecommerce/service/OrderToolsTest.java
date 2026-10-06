package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.OrderDtos.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/phases/P4b.md §6.4 / §8: the buyer id comes only from the ToolContext. */
class OrderToolsTest {

    private static final long USER_A = 41L;

    private final OrderService orderService = mock(OrderService.class);
    private final ToolCallback myOrders = ToolCallbacks.from(new OrderTools(orderService))[0];

    @Test
    void usesOnlyTheUserIdFromTheToolContext() {
        when(orderService.listForBuyer(USER_A)).thenReturn(List.of(new OrderResponse(5L, USER_A, "SECKILL", 9L,
                new BigDecimal("12.50"), "WALLET", "PAID", Instant.parse("2026-10-01T00:00:00Z"))));

        // Whatever the model puts in the arguments, the buyer is the context user.
        String result = myOrders.call("{\"userId\":99,\"buyerId\":99}", new ToolContext(Map.of("userId", USER_A)));

        verify(orderService).listForBuyer(USER_A);
        verify(orderService, never()).listForBuyer(99L);
        assertThat(result).contains("\"buyerId\":41").contains("SECKILL");
    }

    @Test
    void refusesWithoutAContextUser() {
        assertThatThrownBy(() -> myOrders.call("{}", new ToolContext(Map.of("conversationId", "1:a"))))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        verify(orderService, never()).listForBuyer(anyLong());
    }

    @Test
    void inputSchemaHasNoProperties() throws Exception {
        assertThat(myOrders.getToolDefinition().name()).isEqualTo("my_orders");
        JsonNode schema = new ObjectMapper().readTree(myOrders.getToolDefinition().inputSchema());
        JsonNode properties = schema.path("properties");
        assertThat(properties.isMissingNode() || properties.isEmpty()).as(schema.toString()).isTrue();
    }
}
