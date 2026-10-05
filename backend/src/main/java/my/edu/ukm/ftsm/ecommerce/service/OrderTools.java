package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.OrderDtos.OrderResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Local assistant tool for the caller's own orders (docs/phases/P4b.md §6.4). The tool takes no model-supplied input:
 * the buyer id comes only from the {@link ToolContext} that {@link AssistantService} fills from the authenticated JWT,
 * so the model cannot ask for another user's orders.
 */
@Component
public class OrderTools {

    static final String USER_ID = "userId";

    private final OrderService orderService;

    public OrderTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool(name = "my_orders", description = "List the current user's own orders, newest first "
            + "(id, source type, amount in RM, payment method, status, created time).")
    public List<OrderResponse> myOrders(ToolContext toolContext) {
        Object userId = toolContext == null ? null : toolContext.getContext().get(USER_ID);
        if (!(userId instanceof Long id)) {
            throw new IllegalStateException("my_orders called without an authenticated user");
        }
        return orderService.listForBuyer(id);
    }
}
