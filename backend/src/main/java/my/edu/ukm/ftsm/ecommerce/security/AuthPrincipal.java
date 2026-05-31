package my.edu.ukm.ftsm.ecommerce.security;

/**
 * Lightweight authenticated principal stored in the SecurityContext.
 * Retrieve with @AuthenticationPrincipal in controllers.
 */
public record AuthPrincipal(Long userId, String email, String role) {}
