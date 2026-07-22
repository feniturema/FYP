package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.model.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Simulated Malaysian FPX bank transfer. Defaults to stable success for demos. */
@Component
public class MockFpxStrategy implements PaymentStrategy {

    private final int successRatePercent;

    public MockFpxStrategy(@Value("${app.payment.mock-fpx-success-rate:100}") int successRatePercent) {
        this.successRatePercent = Math.max(0, Math.min(100, successRatePercent));
    }

    @Override
    public String method() {
        return "MOCK_FPX";
    }

    @Override
    public boolean pay(Order order) {
        return ThreadLocalRandom.current().nextInt(100) < successRatePercent;
    }
}
