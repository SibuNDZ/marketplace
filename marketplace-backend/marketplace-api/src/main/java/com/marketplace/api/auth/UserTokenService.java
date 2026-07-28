package com.marketplace.api.auth;

import com.marketplace.api.entity.TokenPurpose;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserToken;
import com.marketplace.api.repository.UserTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Issue and consume the single-use secrets that back email verification and
 * password reset. Deliberately one service for both: the mechanics are
 * identical and only the expiry window and purpose differ.
 *
 * Storage follows RefreshTokenService exactly — raw UUID out, SHA-256 hex
 * digest in. The comparison is therefore hash-to-hash, so a presented token
 * that is not in the table simply misses the index lookup.
 *
 * Expiry windows differ on purpose. Verification links are long-lived
 * because people register on a phone and open mail on a laptop hours later,
 * and the cost of a stale one is a resend. Reset links are short because
 * they are an account-takeover primitive sitting in an inbox.
 */
@Service
public class UserTokenService {

    private final UserTokenRepository tokenRepository;
    private final long verificationExpirySeconds;
    private final long resetExpirySeconds;

    public UserTokenService(
            UserTokenRepository tokenRepository,
            @Value("${app.tokens.verification-expiry-seconds:86400}") long verificationExpirySeconds,
            @Value("${app.tokens.reset-expiry-seconds:3600}") long resetExpirySeconds) {
        this.tokenRepository = tokenRepository;
        this.verificationExpirySeconds = verificationExpirySeconds;
        this.resetExpirySeconds = resetExpirySeconds;
    }

    /**
     * Issue a token, invalidating any outstanding one of the same purpose
     * first. Returns the RAW value — the only time it exists anywhere, and
     * the caller's job to put it in an email and then forget it.
     */
    @Transactional
    public String issue(User user, TokenPurpose purpose) {
        tokenRepository.consumeAllOutstanding(user.getId(), purpose, LocalDateTime.now());

        String raw = UUID.randomUUID().toString();

        UserToken token = new UserToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(sha256Hex(raw));
        token.setIssuedAt(LocalDateTime.now());
        token.setExpiresAt(LocalDateTime.now().plusSeconds(expirySecondsFor(purpose)));
        tokenRepository.save(token);

        return raw;
    }

    /**
     * Validate and burn a token, returning its owner.
     *
     * Every failure mode throws the SAME exception type with a purpose-
     * appropriate message. Distinguishing "no such token" from "expired"
     * from "already used" tells someone probing links which guesses were
     * near misses, and the user-facing remedy is identical in all three
     * cases: request a fresh one.
     */
    @Transactional
    public User consume(String raw, TokenPurpose purpose) {
        UserToken token = tokenRepository.findByTokenHash(sha256Hex(raw))
                .filter(t -> t.getPurpose() == purpose)
                .orElseThrow(() -> new InvalidTokenException(purpose));

        if (token.getConsumedAt() != null) {
            throw new InvalidTokenException(purpose);
        }
        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            throw new InvalidTokenException(purpose);
        }

        token.setConsumedAt(LocalDateTime.now());
        return token.getUser();
    }

    /** Burn outstanding tokens without issuing a replacement. */
    @Transactional
    public void revokeAll(Long userId, TokenPurpose purpose) {
        tokenRepository.consumeAllOutstanding(userId, purpose, LocalDateTime.now());
    }

    private long expirySecondsFor(TokenPurpose purpose) {
        return purpose == TokenPurpose.PASSWORD_RESET
                ? resetExpirySeconds
                : verificationExpirySeconds;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — this is unreachable
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(TokenPurpose purpose) {
            super(purpose == TokenPurpose.PASSWORD_RESET
                    ? "This password reset link is invalid or has expired. Request a new one."
                    : "This verification link is invalid or has expired. Request a new one.");
        }
    }
}
