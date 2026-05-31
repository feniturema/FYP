package my.edu.ukm.ftsm.ecommerce.service.strategy;

import my.edu.ukm.ftsm.ecommerce.model.Order;

public interface PaymentStrategy {

    /** Method identifier matched against the request, e.g. "FAKE_WALLET". */
    String method();

    /** Simulate a charge; returns true on success. */
    boolean pay(Order order);
}
