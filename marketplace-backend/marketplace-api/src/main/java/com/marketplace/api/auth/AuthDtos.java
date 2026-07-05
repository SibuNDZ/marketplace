package com.marketplace.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Auth API contracts. ADMIN is excluded from the registration role pattern —
 * privilege escalation via the public registration endpoint must be
 * structurally impossible, not just unchecked. Admins are created by other
 * admins or by seed data.
 */
public class AuthDtos {

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 100,
                    message = "Password must be 8-100 characters") String password,
            @NotBlank @Size(max = 100) String fullName,
            @Pattern(regexp = "CUSTOMER|VENDOR",
                    message = "Role must be CUSTOMER or VENDOR") String role
    ) {
        public String roleOrDefault() {
            return role == null || role.isBlank() ? "CUSTOMER" : role;
        }
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank String refreshToken
    ) {}

    public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            Long userId,
            String email,
            String role,
            String refreshToken,
            long refreshExpiresInSeconds
    ) {
        public static AuthResponse bearer(String token, long expiresIn,
                                          Long userId, String email, String role,
                                          String refreshToken, long refreshExpiresIn) {
            return new AuthResponse(token, "Bearer", expiresIn, userId, email, role,
                    refreshToken, refreshExpiresIn);
        }
    }
}
