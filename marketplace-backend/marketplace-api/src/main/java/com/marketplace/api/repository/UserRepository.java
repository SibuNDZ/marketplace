package com.marketplace.api.repository;

import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

