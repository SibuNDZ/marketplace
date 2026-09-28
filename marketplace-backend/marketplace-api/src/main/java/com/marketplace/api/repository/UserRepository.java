package com.marketplace.api.repository;

import com.marketplace.api.entity.ReferralSource;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    // Google sign-in join key (V30): lookup is sub-first because emails can
    // change at Google while sub cannot.
    Optional<User> findByGoogleSub(String googleSub);

    boolean existsByEmail(String email);

    /** Usernames are stored lowercase; callers must normalise before asking. */
    boolean existsByUsername(String username);

    boolean existsByRole(UserRole role);

    /**
     * Attribution counts (V33). An interface projection rather than a
     * constructor expression because {@code source} is legitimately NULL for
     * every vendor who signed up before the question existed, and that NULL
     * bucket is the number that says how much of the answer is still unknown.
     * Dropping it would make a thin sample look complete.
     */
    interface ReferralSourceCount {
        ReferralSource getSource();

        long getTotal();
    }

    @Query("""
            SELECT u.referralSource AS source, COUNT(u) AS total
            FROM User u
            WHERE u.role = :role
            GROUP BY u.referralSource
            ORDER BY COUNT(u) DESC
            """)
    List<ReferralSourceCount> countByReferralSource(@Param("role") UserRole role);

    /**
     * The free-text labels behind OTHER, newest first. This is how a channel
     * nobody anticipated announces itself: three vendors writing the same
     * thing here is the argument for giving it its own enum constant.
     */
    @Query("""
            SELECT u.referralSourceDetail
            FROM User u
            WHERE u.role = :role
              AND u.referralSource = com.marketplace.api.entity.ReferralSource.OTHER
              AND u.referralSourceDetail IS NOT NULL
            ORDER BY u.id DESC
            """)
    List<String> otherReferralDetails(@Param("role") UserRole role);
}

