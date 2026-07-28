package com.marketplace.api.repository;

import com.marketplace.api.entity.TokenPurpose;
import com.marketplace.api.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHash(String tokenHash);

    /**
     * Burn every outstanding token of one purpose for one user.
     *
     * Called before issuing a replacement, so "resend verification email"
     * does not leave three working links in three inboxes, and called again
     * after a successful password reset so any other reset link someone
     * requested is dead. Marking rather than deleting keeps the audit trail.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE UserToken t SET t.consumedAt = :now
           WHERE t.user.id = :userId AND t.purpose = :purpose AND t.consumedAt IS NULL
           """)
    int consumeAllOutstanding(@Param("userId") Long userId,
                              @Param("purpose") TokenPurpose purpose,
                              @Param("now") LocalDateTime now);
}
