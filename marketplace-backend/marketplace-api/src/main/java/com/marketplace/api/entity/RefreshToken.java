package com.marketplace.api.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One row per issued refresh token. The raw token is never stored — only
 * its SHA-256 hex digest (64 chars). The raw value is returned to the
 * client at issue time and discarded server-side immediately after.
 *
 * revoked_at IS NULL means the token is still valid (subject to expires_at).
 * revoked_at IS NOT NULL means either it was rotated normally or a reuse
 * event forced revocation of the entire user session.
 *
 * No BaseEntity: this table has no updated_at column and the three time
 * columns (issued_at, expires_at, revoked_at) are set explicitly by
 * RefreshTokenService, not by Spring Data auditing.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public Long getId()                    { return id; }
    public User getUser()                  { return user; }
    public void setUser(User user)         { this.user = user; }
    public String getTokenHash()           { return tokenHash; }
    public void setTokenHash(String h)     { this.tokenHash = h; }
    public LocalDateTime getIssuedAt()     { return issuedAt; }
    public void setIssuedAt(LocalDateTime t) { this.issuedAt = t; }
    public LocalDateTime getExpiresAt()    { return expiresAt; }
    public void setExpiresAt(LocalDateTime t) { this.expiresAt = t; }
    public LocalDateTime getRevokedAt()    { return revokedAt; }
    public void setRevokedAt(LocalDateTime t) { this.revokedAt = t; }
}
