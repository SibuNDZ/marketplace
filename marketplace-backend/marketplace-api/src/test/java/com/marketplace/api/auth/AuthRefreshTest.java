package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.AuthResponse;
import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh token integration tests.
 *
 * Each test registers its own user so there is no shared state between tests.
 * TestContainers applies V1–V5 migrations on startup — the V5 refresh_tokens
 * table must exist before any test runs.
 *
 * Access token expiry is not tested directly (waiting 15 minutes is
 * impractical); the config change (86400s → 900s) is verified via
 * response.expiresInSeconds().
 */
@Testcontainers
@SpringBootTest
class AuthRefreshTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Force short expiry so tests can assert the new default
        registry.add("app.jwt.expiry-seconds", () -> "900");
    }

    @Autowired AuthService          authService;
    @Autowired RefreshTokenService  refreshTokenService;
    @Autowired UserRepository       userRepository;

    // ---- helpers --------------------------------------------------------

    private AuthResponse register(String tag) {
        return authService.register(new RegisterRequest(
                tag + "@refresh-test.local",
                "password123",
                tag + " User",
                "CUSTOMER"));
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void login_returns_accessToken_and_refreshToken() {
        AuthResponse r = register("rt-login1");

        assertThat(r.accessToken()).isNotBlank();
        assertThat(r.tokenType()).isEqualTo("Bearer");
        assertThat(r.expiresInSeconds()).isEqualTo(900);      // new 15-minute default
        assertThat(r.refreshToken()).isNotBlank();
        assertThat(r.refreshExpiresInSeconds()).isEqualTo(604800); // 7 days
    }

    @Test
    void refresh_rotates_tokens_and_old_refresh_is_rejected() {
        AuthResponse first = register("rt-rotate1");
        String oldRefresh = first.refreshToken();

        AuthResponse second = authService.refresh(oldRefresh);
        assertThat(second.accessToken()).isNotBlank();
        assertThat(second.refreshToken())
                .isNotBlank()
                .isNotEqualTo(oldRefresh);       // genuinely new token

        // Old token is revoked — presenting it again triggers reuse detection
        assertThatThrownBy(() -> authService.refresh(oldRefresh))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void reuseDetection_revokesAllSessions() {
        AuthResponse first = register("rt-reuse1");
        String R1 = first.refreshToken();

        AuthResponse second = authService.refresh(R1);
        String R2 = second.refreshToken();

        // Presenting R1 again: reuse detected → all sessions revoked
        assertThatThrownBy(() -> authService.refresh(R1))
                .isInstanceOf(BadCredentialsException.class);

        // R2 was active when reuse detection fired — all tokens were revoked,
        // so R2 is also dead (force re-login everywhere)
        assertThatThrownBy(() -> authService.refresh(R2))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void logout_revokes_refresh_token() {
        AuthResponse response = register("rt-logout1");
        String refreshToken = response.refreshToken();

        authService.logout(refreshToken);

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refresh_with_unknown_token_rejected() {
        assertThatThrownBy(() -> authService.refresh("not-a-real-token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void logout_is_idempotent() {
        AuthResponse response = register("rt-idempotent1");
        String refreshToken = response.refreshToken();

        authService.logout(refreshToken);
        authService.logout(refreshToken); // second call must not throw
    }
}
