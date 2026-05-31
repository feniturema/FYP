package my.edu.ukm.ftsm.ecommerce.security;

import my.edu.ukm.ftsm.ecommerce.exception.UnauthorizedDomainException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Enforces that only @ukm.edu.my and @siswa.ukm.edu.my addresses may register.
 */
@Component
public class UkmEmailValidator {

    private static final Pattern UKM_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@(siswa\\.)?ukm\\.edu\\.my$");

    public boolean isValid(String email) {
        return email != null && UKM_PATTERN.matcher(email.trim().toLowerCase()).matches();
    }

    public void assertValid(String email) {
        if (!isValid(email)) {
            throw new UnauthorizedDomainException(
                    "Only @ukm.edu.my or @siswa.ukm.edu.my email addresses are allowed.");
        }
    }
}
