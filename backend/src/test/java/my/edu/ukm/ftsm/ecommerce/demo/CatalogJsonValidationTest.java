package my.edu.ukm.ftsm.ecommerce.demo;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** docs/phases/P5a.md §6.2 / §8: the committed catalogue validates; three broken fixtures are rejected by id. */
class CatalogJsonValidationTest {

    @Test
    void theRealCatalogueIsValidAndHasTheExpectedShape() {
        CatalogJson c = CatalogJson.load().validate();
        assertThat(c.products()).hasSize(400);
        assertThat(c.items()).hasSize(200);
        assertThat(c.products()).extracting(CatalogJson.ProductEntry::id).containsSequence(1001L, 1002L).contains(1400L);
        assertThat(c.items()).extracting(CatalogJson.ItemEntry::id).contains(5001L, 5200L);
        Map<String, Long> perCategory = c.products().stream()
                .collect(Collectors.groupingBy(CatalogJson.ProductEntry::category, Collectors.counting()));
        assertThat(perCategory).hasSize(10);
        assertThat((Collection<Long>) perCategory.values()).allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(20));
    }

    @Test
    void duplicateIdIsRejected() {
        assertThatThrownBy(() -> read("bad-duplicate-id.json").validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("product 1001: duplicate id");
    }

    @Test
    void unknownCategoryIsRejected() {
        assertThatThrownBy(() -> read("bad-category.json").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product 1002: unknown category 'Gadgets'");
    }

    @Test
    void unknownSellerKeyIsRejected() {
        assertThatThrownBy(() -> read("bad-seller.json").validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("item 5003: unknown sellerKey 'seller21'");
    }

    private static CatalogJson read(String name) {
        return CatalogJson.read(new ClassPathResource("demo/" + name));
    }
}
