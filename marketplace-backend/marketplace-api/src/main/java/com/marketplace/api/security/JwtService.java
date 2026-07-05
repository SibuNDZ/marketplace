package com.marketplace.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Token issuing and parsing, isolated from Spring Security so it can be unit
 * tested without a web context. Uses the jjwt 0.12.x API (parser().verifyWith),
 * NOT the 0.11.x API (parserBuilder().setSigningKey) most tutorials show.
 *
 * Subject = user id (stable, not mutable email). Role is embedded as a claim
 * for debugging, but the filter re-loads the user from DB on every request so
 * bans/role-downgrades take effect immediately without waiting for token expiry.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String base64Secret,
            @Value("${app.jwt.expiry-seconds:86400}") long expirySeconds) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.expirySeconds = expirySeconds;
    }

    public String generateToken(Long userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Returns the user id if the token is authentic and unexpired, empty
     * otherwise. All failure reasons collapse to Optional.empty() — callers
     * treat them identically (unauthenticated).
     */
    public Optional<Long> validateAndGetUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }
}
