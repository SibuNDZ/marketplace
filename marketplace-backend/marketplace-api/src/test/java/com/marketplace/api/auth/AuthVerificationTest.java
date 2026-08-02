package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.email.EmailService;
import com.marketplace.api.auth.AuthDtos.RegisterResponse;
import com.marketplace.api.auth.AuthService.EmailNotVerifiedException;
import com.marketplace.api.auth.AuthService.UsernameTakenException;
import com.marketplace.api.entity.TokenPurpose;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.UserTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Email verification and password reset.
 *
 * No RESEND_API_KEY is configured in tests, so EmailService logs instead of
 * sending and reports emailSent=false. That is the point of it returning a
 * flag rather than throwing: these tests exercise the full flow with no
 * outbound network call and no mock, and the "provider is down" path is the
 * default rather than a special case someone has to remember to simulate.
 *
 * Raw tokens never leave the service, so tests read the hash rows directly
 * and re-derive nothing — instead they assert on the state transitions the
 * tokens cause.
 */
@Testcontainers
@SpringBootTest
class AuthVerificationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    /**
     * Mocked so these tests control whether the verification email "sent".
     *
     * This used to rely on the blank test API key making every send fail. That
     * was only safe while the auto-verify fail-safe was broken: once it works,
     * a failed send verifies the account, and every gating test below would be
     * asserting against an account that is already verified. Controlling the
     * flag explicitly makes each test say which world it is in.
     */
    @MockBean EmailService emailService;

    @Autowired AuthService          authService;
    @Autowired UserTokenService     userTokenService;
    @Autowired UserRepository       userRepository;
    @Autowired UserTokenRepository  userTokenRepository;

    @BeforeEach
    void emailSendingWorksByDefault() {
        // Default to the healthy provider. Tests that care about the outage
        // path override this explicitly, so the failure case is never implicit.
        given(emailService.sendVerification(any(), any(), any())).willReturn(true);
        given(emailService.sendPasswordReset(any(), any(), any())).willReturn(true);
    }

    private RegisterResponse register(String tag) {
        return authService.register(new RegisterRequest(
                tag + "@verify-test.local", "password123",
                "Test", "User", tag, "CUSTOMER", null));
    }

    @Test
    void register_creates_unverified_account_and_returns_no_session() {
        RegisterResponse r = register("v_basic");

        assertThat(r.email()).isEqualTo("v_basic@verify-test.local");
        assertThat(r.emailSent()).isTrue();

        User user = userRepository.findByEmail("v_basic@verify-test.local").orElseThrow();
        assertThat(user.getIsVerified()).isFalse();
    }

    /**
     * REGRESSION: the fail-safe must actually persist.
     *
     * It previously set isVerified(true) on an entity that
     * UserTokenRepository.consumeAllOutstanding had already detached
     * (@Modifying(clearAutomatically = true)), so the flag was never written
     * while the log announced that it had been. Every registration during a
     * mail outage produced an account that could not log in — the exact
     * lockout the fail-safe exists to prevent. Asserting on the persisted row
     * AND on a successful login, because the log cannot be trusted here.
     */
    @Test
    void failedSend_actuallyPersistsAutoVerification_soTheUserCanLogIn() {
        given(emailService.sendVerification(any(), any(), any())).willReturn(false);

        RegisterResponse r = register("v_failsafe");
        assertThat(r.emailSent()).isFalse();

        User user = userRepository.findByEmail("v_failsafe@verify-test.local").orElseThrow();
        assertThat(user.getIsVerified())
                .as("fail-safe must WRITE the flag, not just log that it did")
                .isTrue();

        // The behaviour that actually matters to the person registering.
        assertThat(authService.login(
                new LoginRequest("v_failsafe@verify-test.local", "password123")))
                .isNotNull();
    }

    @Test
    void unverified_login_is_rejected_even_with_the_correct_password() {
        register("v_gate");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("v_gate@verify-test.local", "password123")))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    void verifying_the_email_unblocks_login() {
        register("v_flow");
        String raw = issuedTokenFor("v_flow@verify-test.local", TokenPurpose.EMAIL_VERIFICATION);

        authService.verifyEmail(raw);

        assertThat(authService.login(
                new LoginRequest("v_flow@verify-test.local", "password123"))
                .accessToken()).isNotBlank();
    }

    @Test
    void a_verification_link_cannot_be_used_twice() {
        register("v_once");
        String raw = issuedTokenFor("v_once@verify-test.local", TokenPurpose.EMAIL_VERIFICATION);
        authService.verifyEmail(raw);

        assertThatThrownBy(() -> authService.verifyEmail(raw))
                .isInstanceOf(UserTokenService.InvalidTokenException.class);
    }

    @Test
    void resending_verification_invalidates_the_previous_link() {
        register("v_resend");
        String first = issuedTokenFor("v_resend@verify-test.local", TokenPurpose.EMAIL_VERIFICATION);

        authService.resendVerification("v_resend@verify-test.local");

        // The superseded link must be dead, or a resend leaves two working
        // links in two inboxes.
        assertThatThrownBy(() -> authService.verifyEmail(first))
                .isInstanceOf(UserTokenService.InvalidTokenException.class);
    }

    @Test
    void reset_password_changes_the_password_and_verifies_the_account() {
        register("v_reset");

        authService.forgotPassword("v_reset@verify-test.local");
        String raw = issuedTokenFor("v_reset@verify-test.local", TokenPurpose.PASSWORD_RESET);

        authService.resetPassword(raw, "brandnewpassword456");

        // Clicking the link proved inbox control, so verification is implied.
        assertThat(authService.login(
                new LoginRequest("v_reset@verify-test.local", "brandnewpassword456"))
                .accessToken()).isNotBlank();
    }

    @Test
    void forgot_password_is_silent_for_an_unknown_address() {
        // Must not throw: a different response for unknown addresses turns
        // this unauthenticated endpoint into an enumeration oracle.
        authService.forgotPassword("nobody-here@verify-test.local");
    }

    @Test
    void duplicate_username_is_rejected_case_insensitively() {
        register("v_dupe");

        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "different-address@verify-test.local", "password123",
                "Other", "Person", "V_DUPE", "CUSTOMER", null)))
                .isInstanceOf(UsernameTakenException.class);
    }

    @Test
    void a_single_name_registers_without_a_surname() {
        // Mononyms are common; the previous fullName split stored "" here
        // and the entity's @NotBlank turned it into a 500.
        RegisterResponse r = authService.register(new RegisterRequest(
                "mononym@verify-test.local", "password123",
                "Sibongile", null, "mononym_user", "CUSTOMER", null));

        assertThat(r.email()).isEqualTo("mononym@verify-test.local");
        User user = userRepository.findByEmail("mononym@verify-test.local").orElseThrow();
        assertThat(user.getLastName()).isEmpty();
    }

    @Test
    void an_expired_token_is_rejected() {
        register("v_expired");
        User user = userRepository.findByEmail("v_expired@verify-test.local").orElseThrow();

        String raw = userTokenService.issue(user, TokenPurpose.PASSWORD_RESET);
        var token = userTokenRepository.findAll().stream()
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .filter(t -> t.getPurpose() == TokenPurpose.PASSWORD_RESET)
                .filter(t -> t.getConsumedAt() == null)
                .findFirst().orElseThrow();
        token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        userTokenRepository.save(token);

        assertThatThrownBy(() -> authService.resetPassword(raw, "anotherpassword789"))
                .isInstanceOf(UserTokenService.InvalidTokenException.class);
    }

    /**
     * Re-issues a token for the user so the test has the raw value. issue()
     * consumes any outstanding token of the same purpose first, so this
     * yields exactly one live token — the same thing the real flow produces.
     */
    private String issuedTokenFor(String email, TokenPurpose purpose) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return userTokenService.issue(user, purpose);
    }
}
