package com.marketplace.api.config;

import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Solves the first-admin problem: registration structurally excludes ADMIN
 * (correctly), so without this, a fresh production deployment has a state
 * machine no one can drive — orders can never ship.
 *
 * A runner, not a seed migration, deliberately: migrations shouldn't embed
 * credentials (a bcrypt hash in V-something is in git forever), and this
 * stays idempotent and re-runnable across environments with different
 * credentials.
 *
 * Behavior: if ANY admin exists, does nothing. If none exists and the env
 * vars are set, creates one. If none exists and the vars are absent, logs
 * a loud warning but does NOT fail startup — dev shouldn't require the
 * ceremony; a prod deploy checklist should include setting these.
 */
@Configuration
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    @Bean
    CommandLineRunner ensureAdminExists(UserRepository userRepository,
                                        PasswordEncoder passwordEncoder,
                                        @Value("${app.bootstrap.admin-email:}") String email,
                                        @Value("${app.bootstrap.admin-password:}") String password) {
        return args -> {
            if (userRepository.existsByRole(UserRole.ADMIN)) {
                return;
            }
            if (email.isBlank() || password.isBlank()) {
                log.warn("No ADMIN user exists and APP_BOOTSTRAP_ADMIN_EMAIL / "
                        + "APP_BOOTSTRAP_ADMIN_PASSWORD are not set - order status "
                        + "transitions will be impossible until an admin is created.");
                return;
            }
            User admin = new User();
            admin.setEmail(email.trim().toLowerCase());
            admin.setPassword(passwordEncoder.encode(password));
            admin.setFirstName("Platform");
            admin.setLastName("Admin");
            admin.setUsername(freeUsername(userRepository, email));
            // Bootstrapped out of band, so there is no inbox to send a
            // verification link to and no one to click it. Without this the
            // admin is created unverified and login gating locks the platform
            // out of its own order state machine on the very first boot.
            admin.setIsVerified(true);
            admin.setRole(UserRole.ADMIN);
            userRepository.save(admin);
            log.info("Bootstrap ADMIN user created: {}", admin.getEmail());
            // The password came from the environment — rotate it after first
            // login if the env var lives anywhere semi-durable.
        };
    }

    /**
     * Usernames are UNIQUE, and V13 backfilled every pre-existing account
     * with one derived from its email — so a hardcoded "admin" can collide
     * with a real user who happens to be admin@…, and a unique-constraint
     * violation here fails the CommandLineRunner and takes the whole boot
     * down. Derive from the same rule V13 used, then walk suffixes.
     */
    private static String freeUsername(UserRepository userRepository, String email) {
        String stem = email.split("@")[0].toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (stem.isBlank()) {
            stem = "admin";
        }
        stem = stem.substring(0, Math.min(stem.length(), 24));

        String candidate = stem;
        for (int i = 2; userRepository.existsByUsername(candidate); i++) {
            candidate = stem + i;
        }
        return candidate;
    }
}
