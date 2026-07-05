package com.marketplace.api.repository;

import com.marketplace.api.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Load the token with its user eagerly so callers can use the User
     * after the (REQUIRES_NEW) transaction in rotate() has committed and closed.
     */
    @Query("SELECT rt FROM RefreshToken rt JOIN FETCH rt.user WHERE rt.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHash(@Param("hash") String hash);

    /**
     * Nuclear option for reuse detection: revokes every active token for a
     * user in one UPDATE. Called when a revoked token is presented — implies
     * either a replay bug or a stolen token; force re-login everywhere is the
     * correct RFC 6819 response.
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
            SET rt.revokedAt = :now
            WHERE rt.user.id = :userId
              AND rt.revokedAt IS NULL
            """)
    void revokeAllActiveByUserId(@Param("userId") Long userId,
                                 @Param("now") LocalDateTime now);
}
