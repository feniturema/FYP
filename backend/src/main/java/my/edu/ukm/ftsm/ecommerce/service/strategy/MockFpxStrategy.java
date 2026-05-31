package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.model.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Simulated Malaysian FPX bank transfer — mostly succeeds, occasionally fails (demo realism). */
@Component
public class MockFpxStrategy implements PaymentStrategy {

    @Override
    public String method() {
        return "MOCK_FPX";
    }

    @Override
    public boolean pay(Order order) {
        // 90% success to demonstrate FAILED order handling.
        return ThreadLocalRandom.current().nextInt(100) < 90;
    }
}
