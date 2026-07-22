package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStrategyFactoryTest {

    @Test
    void resolvesKnownStrategies() {
        PaymentStrategyFactory factory = new PaymentStrategyFactory(List.of(
                new FakeWalletStrategy(),
                new MockFpxStrategy(100)
        ));

        assertThat(factory.resolve("FAKE_WALLET")).isInstanceOf(FakeWalletStrategy.class);
        assertThat(factory.resolve("MOCK_FPX")).isInstanceOf(MockFpxStrategy.class);
    }

    @Test
    void rejectsUnknownStrategy() {
        PaymentStrategyFactory factory = new PaymentStrategyFactory(List.of(new FakeWalletStrategy()));

        assertThatThrownBy(() -> factory.resolve("CARD"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported payment method");
    }
}
