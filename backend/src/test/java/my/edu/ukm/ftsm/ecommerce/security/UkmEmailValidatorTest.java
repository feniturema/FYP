package my.edu.ukm.ftsm.ecommerce.security;

import my.edu.ukm.ftsm.ecommerce.exception.UnauthorizedDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UkmEmailValidatorTest {

    private final UkmEmailValidator validator = new UkmEmailValidator();

    @Test
    void acceptsUkmDomains() {
        assertThat(validator.isValid("student@ukm.edu.my")).isTrue();
        assertThat(validator.isValid("student@siswa.ukm.edu.my")).isTrue();
    }

    @Test
    void rejectsNonUkmDomains() {
        assertThat(validator.isValid("student@example.com")).isFalse();
        assertThatThrownBy(() -> validator.assertValid("student@gmail.com"))
                .isInstanceOf(UnauthorizedDomainException.class);
    }
}
