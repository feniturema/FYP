package my.edu.ukm.ftsm.ecommerce.demo;

import java.util.List;
import java.util.Map;

/** Outcome of one demo import (docs/phases/P5a.md §6.2). */
public record ImportReport(int sellersCreated, int sellersReused, int productsInserted, int itemsInserted,
                           int unchanged, List<Conflict> conflicts) {

    /** A row kept as it is: field -> [database value, catalogue value]. */
    public record Conflict(String ref, Map<String, List<String>> diffs) {}

    public int inserted() {
        return productsInserted + itemsInserted;
    }

    public String summary() {
        return "inserted " + inserted() + " (products " + productsInserted + ", items " + itemsInserted + "), unchanged "
                + unchanged + ", conflicts " + conflicts.size() + ", sellers created " + sellersCreated + ", reused "
                + sellersReused;
    }
}
