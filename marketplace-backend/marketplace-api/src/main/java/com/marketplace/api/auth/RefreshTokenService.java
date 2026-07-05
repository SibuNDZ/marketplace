package com.marketplace.api.auth;

import com.marketplace.api.entity.RefreshToken;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;

/**
 * Opaque refresh token lifecycle: issue, rotate, revoke.
 *
 * Raw tokens are UUIDs. Only the SHA-256 hex digest is persisted — a stolen
 * DB row is useless without the raw value. The raw value crosses the wire
 * exactly once (at issue) and is never stored server-side.
 *
 * Rotation is atomic at the service level: the old token is marked revoked
 * before the new one is issued. Under concurrent rotation of the same token,
 * both requests may succeed in a race before revocation is visible; this is
 * a known trade-off for stateless token storage without SELECT FOR UPDATE.
 * Production hardening: add @Lock(PESSIMISTIC_WRITE) on findByTokenHash and
 * accept the serialization cost.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshExpirySeconds;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.jwt.refresh-expiry-seconds:604800}") long refreshExpirySeconds) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshExpirySeconds = refreshExpirySeconds;
    }

    /** Issue a new refresh token for a user. Returns the raw (unhashed) value. */
    @Transactional
    public String issue(User user) {
        String raw  = UUID.randomUUID().toString();
        String hash = sha256Hex(raw);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash);
        token.setIssuedAt(LocalDateTime.now());
        token.setExpiresAt(LocalDateTime.now().plusSeconds(refreshExpirySeconds));
        refreshTokenRepository.save(token);

        return raw;
    }

    /**
     * Validate the presented token, mark it revoked, and return the owning
     * User so the caller can issue a new access + refresh token pair.
     *
     * Reuse detection: a revoked token → revoke ALL active tokens for that
     * user (force re-login everywhere) and throw. This is the RFC 6819
     * recommended response — an already-rotated token being presented means
     * either a replay bug or a stolen token.
     *
     * REQUIRES_NEW + noRollbackFor: this method commits its own transaction
     * (so revocations persist) then throws. The caller's outer transaction
     * rolls back normally — nothing was written there yet.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
                   noRollbackFor = BadCredentialsException.class)
    public User rotate(String raw) {
        String hash  = sha256Hex(raw);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (token.getRevokedAt() != null) {
            Long userId = token.getUser().getId();
            log.warn("Refresh token reuse detected for user {} — revoking all sessions", userId);
            refreshTokenRepository.revokeAllActiveByUserId(userId, LocalDateTime.now());
            throw new BadCredentialsException("Invalid refresh token");
        }

        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            token.setRevokedAt(LocalDateTime.now());
            throw new BadCredentialsException("Refresh token expired");
        }

        token.setRevokedAt(LocalDateTime.now());  // rotate: old is dead
        return token.getUser();
    }

    /** Revoke a single token (logout). Idempotent: already-revoked tokens are silently ignored. */
    @Transactional
    public void revoke(String raw) {
        String hash = sha256Hex(raw);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(LocalDateTime.now());
            }
        });
    }

    public long getRefreshExpirySeconds() {
        return refreshExpirySeconds;
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
}
