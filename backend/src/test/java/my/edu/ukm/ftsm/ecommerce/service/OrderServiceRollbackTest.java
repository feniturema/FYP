package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.OrderDtos.CreateOrderRequest;
import my.edu.ukm.ftsm.ecommerce.dto.OrderDtos.OrderResponse;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.service.strategy.FakeWalletStrategy;
import my.edu.ukm.ftsm.ecommerce.service.strategy.MockFpxStrategy;
import my.edu.ukm.ftsm.ecommerce.service.strategy.PaymentStrategyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conditional updates (docs/phases/P2.md §6.9) keep {@code OrderService.create}'s transactional rollback:
 * a failure after the stock/status update must restore it. BusinessException maps to HTTP 400.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-rollback;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OrderService.class, PaymentStrategyFactory.class, FakeWalletStrategy.class, MockFpxStrategy.class})
class OrderServiceRollbackTest {

    private static final long BUYER = 100L;

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductRepository products;
    @Autowired
    private ItemRepository items;
    @Autowired
    private OrderRepository orders;

    @AfterEach
    void cleanUp() {
        orders.deleteAll();
        products.deleteAll();
        items.deleteAll();
    }

    @Test
    void outOfStockProductIsABusinessError() {
        Product p = product(0);

        assertThatThrownBy(() -> buy("B2C_PRODUCT", p.getId(), "FAKE_WALLET"))
                .isInstanceOf(BusinessException.class).hasMessage("Product is out of stock.");
        assertThat(orders.count()).isZero();
    }

    @Test
    void failedSettlementRestoresProductStock() {
        Product p = product(3);

        assertThatThrownBy(() -> buy("B2C_PRODUCT", p.getId(), "NOPE")).isInstanceOf(BusinessException.class);

        assertThat(products.findById(p.getId()).orElseThrow().getTotalStock()).isEqualTo(3);
        assertThat(orders.count()).isZero();
    }

    @Test
    void failedSettlementRestoresItemToActive() {
        Item i = item();

        assertThatThrownBy(() -> buy("C2C_ITEM", i.getId(), "NOPE")).isInstanceOf(BusinessException.class);

        assertThat(items.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Item.Status.ACTIVE);
        assertThat(orders.count()).isZero();
    }

    @Test
    void secondPurchaseOfTheLastUnitIsRejected() {
        Product p = product(1);

        OrderResponse first = buy("B2C_PRODUCT", p.getId(), "FAKE_WALLET");
        assertThatThrownBy(() -> buy("B2C_PRODUCT", p.getId(), "FAKE_WALLET"))
                .isInstanceOf(BusinessException.class).hasMessage("Product is out of stock.");

        assertThat(first.status()).isEqualTo("PAID");
        assertThat(products.findById(p.getId()).orElseThrow().getTotalStock()).isZero();
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void soldItemCannotBeBoughtAgain() {
        Item i = item();

        buy("C2C_ITEM", i.getId(), "FAKE_WALLET");
        assertThatThrownBy(() -> buy("C2C_ITEM", i.getId(), "FAKE_WALLET"))
                .isInstanceOf(BusinessException.class).hasMessage("Item is no longer available.");

        assertThat(items.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Item.Status.SOLD);
        assertThat(orders.count()).isEqualTo(1);
    }

    private OrderResponse buy(String sourceType, Long refId, String paymentMethod) {
        return orderService.create(BUYER, new CreateOrderRequest(sourceType, refId, paymentMethod));
    }

    private Product product(int stock) {
        return products.save(Product.builder().name("Mug").price(new BigDecimal("12.50")).totalStock(stock).build());
    }

    private Item item() {
        return items.save(Item.builder().sellerId(200L).title("Calculator").price(new BigDecimal("30.00"))
                .status(Item.Status.ACTIVE).build());
    }
}
