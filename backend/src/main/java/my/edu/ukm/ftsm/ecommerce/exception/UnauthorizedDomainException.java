package my.edu.ukm.ftsm.ecommerce.exception;

/** Thrown when an email is not within the allowed UKM domains. */
public class UnauthorizedDomainException extends RuntimeException {
    public UnauthorizedDomainException(String message) {
        super(message);
    }
}
