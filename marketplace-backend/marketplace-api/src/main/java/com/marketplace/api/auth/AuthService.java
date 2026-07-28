package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.AuthResponse;
import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.auth.AuthDtos.RegisterResponse;
import com.marketplace.api.email.EmailService;
import com.marketplace.api.entity.TokenPurpose;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, and the email-driven flows around them.
 *
 * Login failure is ONE exception whether the email doesn't exist or the
 * password is wrong — distinguishing them would turn this endpoint into a
 * user-enumeration oracle.
 *
 * passwordEncoder.matches() runs even when the user doesn't exist (against a
 * dummy hash). Without this, "unknown email" returns in ~1ms and "known email,
 * wrong password" in ~100ms (bcrypt cost) — a timing side channel that
 * enumerates users just as effectively as different error messages.
 *
 * EmailNotVerifiedException is the ONE deliberate exception to that rule.
 * It only fires AFTER the password has already been verified, so it tells a
 * caller nothing they did not already prove they knew, and the alternative
 * is a correct password returning "invalid credentials" forever with no
 * route to the resend button.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String DUMMY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B0mzVWkzZg9Yx0J8bIhH1S6y5tXe";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserTokenService userTokenService;
    private final EmailService emailService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       UserTokenService userTokenService,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userTokenService = userTokenService;
        this.emailService = emailService;
    }

    /**
     * Create the account unverified and email a confirmation link.
     *
     * Returns no tokens: login is gated on verification, so a session issued
     * here would be unusable.
     *
     * A failed send does NOT roll this back. The account is kept and
     * emailSent=false is reported so the UI can offer a resend. Rolling back
     * would be tidier state, but a transient Resend outage would then cost
     * the user their registration, and their retry would collide with
     * nothing — or worse, with a half-created account.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String username = request.username().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        if (userRepository.existsByUsername(username)) {
            throw new UsernameTakenException(username);
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastNameOrEmpty());
        user.setRole(UserRole.valueOf(request.roleOrDefault()));
        user.setIsVerified(false);

        User saved = userRepository.save(user);

        String rawToken = userTokenService.issue(saved, TokenPurpose.EMAIL_VERIFICATION);
        boolean sent = emailService.sendVerification(
                saved.getEmail(), saved.getFirstName(), rawToken);

        if (!sent) {
            // FAIL-SAFE, and a deliberate security trade.
            //
            // Login is gated on is_verified. If the mail provider is
            // misconfigured or down, an unverified account is an account that
            // can NEVER log in: the resend button hits the same broken sender,
            // and registering again returns "email already registered". The
            // user is permanently locked out of an address they own.
            //
            // So a send failure degrades to verified rather than stranding
            // them. The cost is real: while sending is broken, nobody is
            // proving they own their address, which is the whole point of
            // verification. That is the right way round — an email outage
            // becomes weaker signup checks, not a total signup outage — but it
            // is ONLY safe because it is loud. This log line is the alert.
            saved.setIsVerified(true);
            log.error("VERIFICATION EMAIL FAILED for {} — account auto-verified so the "
                    + "user is not locked out. Email verification is currently NOT being "
                    + "enforced; fix the mail provider.", saved.getEmail());
        }

        return new RegisterResponse(saved.getEmail(), sent);
    }

    // login must be read-write: it persists a refresh token
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(email).orElse(null);

        String hashToCheck = user != null ? user.getPassword() : DUMMY_HASH;
        boolean matches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user == null || !matches) {
            throw new BadCredentialsException("Invalid email or password");
        }

        // Only reachable with a correct password, so this leaks nothing an
        // attacker did not already have.
        if (!Boolean.TRUE.equals(user.getIsVerified())) {
            throw new EmailNotVerifiedException(user.getEmail());
        }

        return issueSession(user);
    }

    /** Consume a verification token and mark the account verified. */
    @Transactional
    public void verifyEmail(String rawToken) {
        User user = userTokenService.consume(rawToken, TokenPurpose.EMAIL_VERIFICATION);
        user.setIsVerified(true);
        log.info("Email verified for user {}", user.getId());
    }

    /**
     * Re-send a verification link.
     *
     * Silent for unknown addresses and for accounts that are already
     * verified — this endpoint is unauthenticated, so responding differently
     * would make it an enumeration oracle. The controller returns the same
     * 202 either way.
     */
    @Transactional
    public void resendVerification(String rawEmail) {
        String email = rawEmail.trim().toLowerCase();
        userRepository.findByEmail(email)
                .filter(u -> !Boolean.TRUE.equals(u.getIsVerified()))
                .ifPresent(user -> {
                    String token = userTokenService.issue(user, TokenPurpose.EMAIL_VERIFICATION);
                    emailService.sendVerification(user.getEmail(), user.getFirstName(), token);
                });
    }

    /**
     * Start a password reset. Silent for unknown addresses, same reasoning
     * as resendVerification.
     *
     * Deliberately available to unverified accounts: someone who registered,
     * never received the mail, and has now forgotten the password would
     * otherwise be stuck between two flows that each refuse to help.
     * Completing a reset proves control of the inbox, which is exactly what
     * verification was asking for, so resetPassword marks them verified too.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = rawEmail.trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            String token = userTokenService.issue(user, TokenPurpose.PASSWORD_RESET);
            emailService.sendPasswordReset(user.getEmail(), user.getFirstName(), token);
        });
    }

    /**
     * Consume a reset token and set the new password.
     *
     * Revokes every refresh token for the user. A password reset is what
     * someone does after suspecting compromise, and leaving the attacker's
     * existing session alive would make it ceremonial.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        User user = userTokenService.consume(rawToken, TokenPurpose.PASSWORD_RESET);

        user.setPassword(passwordEncoder.encode(newPassword));

        // Clicking the link proved inbox control, which is all verification
        // ever asked for.
        user.setIsVerified(true);

        refreshTokenService.revokeAllForUser(user.getId());
        userTokenService.revokeAll(user.getId(), TokenPurpose.PASSWORD_RESET);

        log.info("Password reset completed for user {} — all sessions revoked", user.getId());
    }

    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username.trim().toLowerCase());
    }

    /**
     * Rotate-on-use: validate the refresh token, revoke it, issue a new
     * access token + new refresh token. Presenting a revoked token triggers
     * reuse detection and revokes all sessions for that user.
     *
     * rotate() runs in its own REQUIRES_NEW transaction (see RefreshTokenService)
     * so revocations are committed before the exception propagates.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        User user = refreshTokenService.rotate(rawRefreshToken);
        return issueSession(user);
    }

    /** Revoke a single refresh token (single-device logout). */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private AuthResponse issueSession(User user) {
        String accessToken  = jwtService.generateToken(user.getId(), user.getRole().name());
        String refreshToken = refreshTokenService.issue(user);
        return AuthResponse.bearer(accessToken, jwtService.getExpirySeconds(),
                user.getId(), user.getEmail(), user.getRole().name(),
                refreshToken, refreshTokenService.getRefreshExpirySeconds());
    }

    public static class EmailAlreadyRegisteredException extends RuntimeException {
        public EmailAlreadyRegisteredException(String email) {
            super("Email already registered: " + email);
        }
    }

    public static class UsernameTakenException extends RuntimeException {
        public UsernameTakenException(String username) {
            super("Username already taken: " + username);
        }
    }

    public static class EmailNotVerifiedException extends RuntimeException {
        public EmailNotVerifiedException(String email) {
            super("Confirm your email address before signing in. "
                    + "We sent a link to " + email + ".");
        }
    }
}
