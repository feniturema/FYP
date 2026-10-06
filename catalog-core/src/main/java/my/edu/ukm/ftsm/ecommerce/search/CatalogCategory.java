package my.edu.ukm.ftsm.ecommerce.search;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The 10 catalogue categories (docs/phases/P5a.md §1). {@link #label()} is the value stored in the
 * {@code category} column ("Apparel"); {@link #parse(String)} accepts the enum name or the label, ignoring case.
 * Used to validate {@code GET /api/search?category=} and, from P7, category input elsewhere.
 */
public enum CatalogCategory {
    APPAREL("Apparel"),
    ACCESSORIES("Accessories"),
    LIFESTYLE("Lifestyle"),
    ELECTRONICS("Electronics"),
    BOOKS("Books"),
    STATIONERY("Stationery"),
    SPORTS("Sports"),
    FOOD("Food"),
    HOME("Home"),
    BEAUTY("Beauty");

    private final String label;

    CatalogCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Case-insensitive match on the enum name or label; empty for null, blank or unknown input. */
    public static Optional<CatalogCategory> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.name().equals(v)).findFirst();
    }

    /** "Apparel, Accessories, …" for error messages. */
    public static String allowedLabels() {
        return Arrays.stream(values()).map(CatalogCategory::label).collect(Collectors.joining(", "));
    }
}
