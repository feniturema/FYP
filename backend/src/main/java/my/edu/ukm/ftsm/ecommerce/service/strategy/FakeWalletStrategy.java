package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.model.Order;
import org.springframework.stereotype.Component;

/** Simulated campus wallet — always succeeds (demo). */
@Component
public class FakeWalletStrategy implements PaymentStrategy {

    @Override
    public String method() {
        return "FAKE_WALLET";
    }

    @Override
    public boolean pay(Order order) {
        // No real movement of funds; demo always succeeds.
        return true;
    }
}
