package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.OrderDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.exception.ResourceNotFoundException;
import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.service.strategy.PaymentStrategy;
import my.edu.ukm.ftsm.ecommerce.service.strategy.PaymentStrategyFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final PaymentStrategyFactory paymentFactory;

    public OrderService(OrderRepository orderRepository, ItemRepository itemRepository,
                        ProductRepository productRepository, PaymentStrategyFactory paymentFactory) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.paymentFactory = paymentFactory;
    }

    @Transactional
    public OrderResponse create(Long buyerId, CreateOrderRequest req) {
        Order.SourceType sourceType;
        try {
            sourceType = Order.SourceType.valueOf(req.sourceType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid sourceType: " + req.sourceType());
        }

        Order order = switch (sourceType) {
            case C2C_ITEM -> buildItemOrder(buyerId, req.refId());
            case B2C_PRODUCT -> buildProductOrder(buyerId, req.refId());
            case SECKILL -> throw new BusinessException("SecKill orders are created via the SecKill endpoint.");
        };
        order.setPaymentMethod(req.paymentMethod());
        order = orderRepository.save(order);

        // attempt payment immediately for the standard (non-seckill) flow
        settle(order, req.paymentMethod());
        return OrderResponse.from(order);
    }

    private Order buildItemOrder(Long buyerId, Long itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));
        if (item.getStatus() != Item.Status.ACTIVE) {
            throw new BusinessException("Item is no longer available.");
        }
        if (item.getSellerId().equals(buyerId)) {
            throw new BusinessException("You cannot buy your own listing.");
        }
        // Conditional ACTIVE -> SOLD in this transaction: concurrent buyers cannot both win, and a
        // later failure (save/settle) rolls the status back.
        if (itemRepository.markSold(itemId, Item.Status.SOLD, Item.Status.ACTIVE) == 0) {
            throw new BusinessException("Item is no longer available.");
        }
        return Order.builder()
                .buyerId(buyerId)
                .sourceType(Order.SourceType.C2C_ITEM)
                .refId(itemId)
                .amount(item.getPrice())
                .status(Order.Status.PENDING)
                .build();
    }

    private Order buildProductOrder(Long buyerId, Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        // Conditional decrement in this transaction (no read-modify-write race; rolled back on failure).
        if (productRepository.decrementStock(productId) == 0) {
            throw new BusinessException("Product is out of stock.");
        }
        return Order.builder()
                .buyerId(buyerId)
                .sourceType(Order.SourceType.B2C_PRODUCT)
                .refId(productId)
                .amount(product.getPrice())
                .status(Order.Status.PENDING)
                .build();
    }

    @Transactional
    public OrderResponse pay(Long buyerId, Long orderId, String paymentMethod) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new BusinessException("Not your order.");
        }
        if (order.getStatus() == Order.Status.PAID) {
            return OrderResponse.from(order);
        }
        order.setPaymentMethod(paymentMethod);
        settle(order, paymentMethod);
        return OrderResponse.from(order);
    }

    /** Runs the chosen payment strategy and updates order status. Used by both flows. */
    @Transactional
    public void settle(Order order, String paymentMethod) {
        PaymentStrategy strategy = paymentFactory.resolve(paymentMethod);
        boolean ok = strategy.pay(order);
        order.setStatus(ok ? Order.Status.PAID : Order.Status.FAILED);
        orderRepository.save(order);
    }

    public List<OrderResponse> listForBuyer(Long buyerId) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
                .map(OrderResponse::from).toList();
    }

    public OrderResponse get(Long buyerId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new BusinessException("Not your order.");
        }
        return OrderResponse.from(order);
    }
}
