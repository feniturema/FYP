package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves a {@link PaymentStrategy} by its method id. */
@Component
public class PaymentStrategyFactory {

    private final Map<String, PaymentStrategy> strategies;

    public PaymentStrategyFactory(List<PaymentStrategy> all) {
        this.strategies = all.stream()
                .collect(Collectors.toMap(PaymentStrategy::method, Function.identity()));
    }

    public PaymentStrategy resolve(String method) {
        PaymentStrategy s = strategies.get(method);
        if (s == null) {
            throw new BusinessException("Unsupported payment method: " + method
                    + ". Supported: " + strategies.keySet());
        }
        return s;
    }
}
