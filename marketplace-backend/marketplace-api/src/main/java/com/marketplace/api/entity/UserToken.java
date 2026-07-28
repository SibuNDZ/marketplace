package com.marketplace.api.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A single-use, expiring secret emailed to a user — currently either an
 * email-verification link or a password-reset link, discriminated by
 * {@link TokenPurpose}.
 *
 * Same storage rule as {@link RefreshToken}: the raw UUID is emailed and
 * never stored, only its SHA-256 hex digest lands here. A leaked database
 * dump therefore cannot be replayed into a password reset, which matters
 * more here than for refresh tokens — a reset token is a full account
 * takeover primitive.
 *
 * consumed_at rather than row deletion: a link clicked twice must be able
 * to say "already used" instead of "invalid", which needs the row to
 * survive. It doubles as the audit trail for when an account was last
 * verified or reset.
 *
 * No BaseEntity, matching RefreshToken: the time columns are set explicitly
 * by the service, not by Spring Data auditing.
 */
@Entity
@Table(name = "user_tokens")
public class UserToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private TokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public Long getId()                        { return id; }
    public User getUser()                      { return user; }
    public void setUser(User user)             { this.user = user; }
    public TokenPurpose getPurpose()           { return purpose; }
    public void setPurpose(TokenPurpose p)     { this.purpose = p; }
    public String getTokenHash()               { return tokenHash; }
    public void setTokenHash(String h)         { this.tokenHash = h; }
    public LocalDateTime getIssuedAt()         { return issuedAt; }
    public void setIssuedAt(LocalDateTime t)   { this.issuedAt = t; }
    public LocalDateTime getExpiresAt()        { return expiresAt; }
    public void setExpiresAt(LocalDateTime t)  { this.expiresAt = t; }
    public LocalDateTime getConsumedAt()       { return consumedAt; }
    public void setConsumedAt(LocalDateTime t) { this.consumedAt = t; }
}
