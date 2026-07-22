package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.model.Order;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockFpxStrategyTest {

    @Test
    void defaultsToGuaranteedSuccessForStableDemos() {
        MockFpxStrategy strategy = new MockFpxStrategy(100);

        assertThat(strategy.pay(Order.builder().build())).isTrue();
    }
}
