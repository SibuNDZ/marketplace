package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.AuthResponse;
import com.marketplace.api.auth.AuthDtos.ForgotPasswordRequest;
import com.marketplace.api.auth.AuthDtos.GoogleSignInRequest;
import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.LogoutRequest;
import com.marketplace.api.auth.AuthDtos.RefreshRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.auth.AuthDtos.RegisterResponse;
import com.marketplace.api.auth.AuthDtos.ResendVerificationRequest;
import com.marketplace.api.auth.AuthDtos.ResetPasswordRequest;
import com.marketplace.api.auth.AuthDtos.UsernameAvailableResponse;
import com.marketplace.api.auth.AuthDtos.VerifyEmailRequest;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    /**
     * Always 202, whether the address is unknown, already verified, or a
     * mail actually went out. Anything else makes this unauthenticated
     * endpoint an enumeration oracle.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    /** Always 202. Same enumeration reasoning as resend-verification. */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * Availability check for the registration form.
     *
     * This one DOES disclose whether a username exists, unavoidably — that
     * is the entire feature, and every site with usernames leaks the same
     * thing by rejecting a taken one at submit. Usernames are public
     * identifiers here, unlike email addresses. It sits behind the auth
     * rate limiter like the rest of /api/v1/auth/**, which bounds scraping.
     */
    @GetMapping("/username-available")
    public UsernameAvailableResponse usernameAvailable(@RequestParam String username) {
        return new UsernameAvailableResponse(
                username, authService.isUsernameAvailable(username));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * "Continue with Google": takes the GIS credential, returns the same
     * AuthResponse shape as /login. 503 when GOOGLE_CLIENT_ID is unset in
     * this environment. Sits behind the same per-IP auth rate limiter as
     * everything else under /api/v1/auth/**.
     */
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleSignInRequest request) {
        return ResponseEntity.ok(authService.googleSignIn(request.credential()));
    }

    /**
     * Rotate-on-use refresh. The old refresh token is immediately revoked;
     * presenting a stale token triggers reuse detection and force-logs-out
     * all devices. Both the new access token and new refresh token are
     * returned — clients must store and use the new refresh token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    /** Single-device logout: revoke the presented refresh token. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Smoke test for the full JWT chain: if this returns your user, the filter,
     * JwtService, and SecurityContext wiring all work.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of(
                "userId", principal.getId(),
                "email", principal.getUsername(),
                "role", principal.getRole()));
    }
}
