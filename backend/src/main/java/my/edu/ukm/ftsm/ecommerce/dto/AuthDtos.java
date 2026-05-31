package my.edu.ukm.ftsm.ecommerce.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Auth-related request/response payloads grouped together. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank String name,
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 100) String password
    ) {}

    public record VerifyOtpRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    public record ResendOtpRequest(
            @Email @NotBlank String email
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(
            String token,
            Long userId,
            String name,
            String email,
            String role
    ) {}

    public record MessageResponse(String message) {}
}
