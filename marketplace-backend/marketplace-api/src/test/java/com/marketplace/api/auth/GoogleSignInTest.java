package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.AuthResponse;
import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.auth.AuthService.GoogleSignInUnavailableException;
import com.marketplace.api.auth.GoogleIdentityVerifier.GoogleIdentity;
import com.marketplace.api.email.EmailService;
import com.marketplace.api.entity.TokenPurpose;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * "Continue with Google" (google-signin.md §5).
 *
 * GoogleIdentityVerifier is the seam: these tests stub it with canned
 * identities, so nothing here touches Google. What they pin is the account
 * model around the verified token — link-or-create rules, the null-password
 * login stance, and the resetPassword escape hatch.
 */
@Testcontainers
@SpringBootTest
class GoogleSignInTest {

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

    @MockBean GoogleIdentityVerifier googleIdentityVerifier;
    @MockBean EmailService emailService;

    @Autowired AuthService authService;
    @Autowired UserTokenService userTokenService;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void configuredByDefault() {
        // The unconfigured case is one explicit test, not the mock's silent
        // default — otherwise every test below would 503 for the wrong reason.
        given(googleIdentityVerifier.isConfigured()).willReturn(true);
        given(emailService.sendVerification(any(), any(), any())).willReturn(true);
    }

    private void stubCredential(String credential, GoogleIdentity identity) {
        given(googleIdentityVerifier.verify(credential)).willReturn(identity);
    }

    @Test
    void createsCustomerAccount_verifiedAndPasswordless() {
        stubCredential("cred-new", new GoogleIdentity(
                "sub-new-1", "Thabo.M@gmail.com", true, "Thabo", "Mokoena"));

        AuthResponse response = authService.googleSignIn("cred-new");

        assertThat(response.accessToken()).isNotBlank();
        User user = userRepository.findByEmail("thabo.m@gmail.com").orElseThrow();
        assertThat(user.getGoogleSub()).isEqualTo("sub-new-1");
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getIsVerified()).isTrue();          // Google proved the inbox
        assertThat(user.getPassword()).isNull();            // Google-only account
        assertThat(user.getFirstName()).isEqualTo("Thabo");
        assertThat(user.getUsername()).isEqualTo("thabo_m"); // local part, normalised
    }

    @Test
    void usernameCollision_getsNumericSuffix() {
        stubCredential("cred-a", new GoogleIdentity(
                "sub-a", "lindiwe@gmail.com", true, "Lindiwe", null));
        stubCredential("cred-b", new GoogleIdentity(
                "sub-b", "lindiwe@outlook.com", true, "Lindiwe", null));

        authService.googleSignIn("cred-a");
        authService.googleSignIn("cred-b");

        assertThat(userRepository.findByEmail("lindiwe@outlook.com").orElseThrow()
                .getUsername()).isEqualTo("lindiwe1");
    }

    @Test
    void linksToExistingAccount_byVerifiedEmail_andMarksVerified() {
        // Registered with a password but never clicked the verification link.
        authService.register(new RegisterRequest(
                "linkme@test.local", "password123", "Link", "", "linkme",
                null, null));
        User before = userRepository.findByEmail("linkme@test.local").orElseThrow();
        assertThat(before.getIsVerified()).isFalse();

        stubCredential("cred-link", new GoogleIdentity(
                "sub-link", "LinkMe@test.local", true, "Link", ""));
        authService.googleSignIn("cred-link");

        User after = userRepository.findByEmail("linkme@test.local").orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());   // linked, not duplicated
        assertThat(after.getGoogleSub()).isEqualTo("sub-link");
        // Google's email_verified is stronger proof than our link.
        assertThat(after.getIsVerified()).isTrue();
        assertThat(after.getPassword()).isNotNull();           // password stays usable
    }

    @Test
    void repeatSignIn_findsAccountBySub_notEmail() {
        stubCredential("cred-r1", new GoogleIdentity(
                "sub-repeat", "repeat@gmail.com", true, "Re", "Peat"));
        authService.googleSignIn("cred-r1");

        // Same sub, email changed at Google: still the same account, and the
        // stored email must NOT silently follow Google's.
        stubCredential("cred-r2", new GoogleIdentity(
                "sub-repeat", "renamed@gmail.com", true, "Re", "Peat"));
        authService.googleSignIn("cred-r2");

        assertThat(userRepository.findByGoogleSub("sub-repeat").orElseThrow()
                .getEmail()).isEqualTo("repeat@gmail.com");
        assertThat(userRepository.findByEmail("renamed@gmail.com")).isEmpty();
    }

    @Test
    void unverifiedGoogleEmail_isRejected_andCreatesNothing() {
        // The account-takeover guard: linking on an unverified Google email
        // would let anyone who claims an address at Google capture the
        // matching marketplace account.
        stubCredential("cred-unverified", new GoogleIdentity(
                "sub-uv", "victim@test.local", false, "Not", "Proved"));

        assertThatThrownBy(() -> authService.googleSignIn("cred-unverified"))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(userRepository.findByEmail("victim@test.local")).isEmpty();
    }

    @Test
    void unconfigured_environmentAnswersUnavailable() {
        given(googleIdentityVerifier.isConfigured()).willReturn(false);

        assertThatThrownBy(() -> authService.googleSignIn("cred-any"))
                .isInstanceOf(GoogleSignInUnavailableException.class);
    }

    @Test
    void passwordLogin_againstGoogleOnlyAccount_failsGenerically() {
        stubCredential("cred-nopw", new GoogleIdentity(
                "sub-nopw", "nopassword@gmail.com", true, "No", "Password"));
        authService.googleSignIn("cred-nopw");

        // Same exception as a wrong password or unknown email — the login
        // endpoint never discloses that an account is Google-backed.
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nopassword@gmail.com", "any-guess-123")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resetPassword_isTheEscapeHatch_forGoogleOnlyAccounts() {
        stubCredential("cred-hatch", new GoogleIdentity(
                "sub-hatch", "hatch@gmail.com", true, "Es", "Cape"));
        authService.googleSignIn("cred-hatch");
        User user = userRepository.findByEmail("hatch@gmail.com").orElseThrow();
        assertThat(user.getPassword()).isNull();

        String rawToken = userTokenService.issue(user, TokenPurpose.PASSWORD_RESET);
        authService.resetPassword(rawToken, "chosen-password-1");

        AuthResponse session = authService.login(
                new LoginRequest("hatch@gmail.com", "chosen-password-1"));
        assertThat(session.accessToken()).isNotBlank();
    }
}
