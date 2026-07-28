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

    /**
     * firstName and lastName are carried SEPARATELY rather than as a single
     * fullName the server splits on whitespace.
     *
     * The old shape joined two form fields client-side and re-split them
     * here on the first space. That round trip was lossy in both directions:
     * a mononym produced an empty lastName, which the entity's @NotBlank
     * then rejected as an opaque 500, and "Mary Jane Smith" silently became
     * first="Mary" last="Jane Smith". The frontend collects two fields and
     * the entity stores two fields; making the wire format agree with both
     * removes the guesswork entirely.
     *
     * lastName is intentionally NOT @NotBlank. Mononyms are common, and a
     * required surname is a validation rule that rejects real people. The
     * column stays NOT NULL and an absent surname is stored as "".
     */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 100,
                    message = "Password must be 8-100 characters") String password,
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            // Case-insensitive here, lowercased in the service — the column
            // has a plain UNIQUE constraint, so "Sibu" and "sibu" must not
            // both be registrable.
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{3,30}$",
                    message = "Username must be 3-30 characters, letters, numbers or underscore")
            String username,
            @Pattern(regexp = "CUSTOMER|VENDOR",
                    message = "Role must be CUSTOMER or VENDOR") String role
    ) {
        public String roleOrDefault() {
            return role == null || role.isBlank() ? "CUSTOMER" : role;
        }

        public String lastNameOrEmpty() {
            return lastName == null ? "" : lastName.trim();
        }
    }

    /**
     * Registration no longer returns tokens. Login is gated on verification,
     * so handing back a session the user cannot use would be a lie, and
     * auto-login would defeat the point of verifying at all.
     *
     * emailSent is surfaced deliberately: when Resend is down the account
     * still exists, and the UI has to say so and offer a resend rather than
     * claiming an email is on its way that never left.
     */
    public record RegisterResponse(
            String email,
            boolean emailSent
    ) {}

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

    public record VerifyEmailRequest(
            @NotBlank String token
    ) {}

    public record ResendVerificationRequest(
            @NotBlank @Email String email
    ) {}

    public record ForgotPasswordRequest(
            @NotBlank @Email String email
    ) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100,
                    message = "Password must be 8-100 characters") String password
    ) {}

    public record UsernameAvailableResponse(
            String username,
            boolean available
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
