package my.edu.ukm.ftsm.ecommerce.exception;

/** Generic recoverable business-rule violation (HTTP 400). */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
