package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.OrderDtos.*;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.OrderService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderResponse> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return orderService.listForBuyer(principal.userId());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        return orderService.get(principal.userId(), id);
    }

    @PostMapping
    public OrderResponse create(@AuthenticationPrincipal AuthPrincipal principal,
                                @Valid @RequestBody CreateOrderRequest req) {
        return orderService.create(principal.userId(), req);
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(@AuthenticationPrincipal AuthPrincipal principal,
                             @PathVariable Long id, @Valid @RequestBody PayRequest req) {
        return orderService.pay(principal.userId(), id, req.paymentMethod());
    }
}
