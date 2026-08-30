package com.marketplace.api.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The landing page's newsletter signup.
 *
 * Always 202: new subscription, repeat subscription, and any future
 * suppressed address all answer identically, so this unauthenticated
 * endpoint discloses nothing about who is on the list (same stance as
 * forgot-password). ON CONFLICT DO NOTHING makes the repeat case a no-op
 * at the database rather than a read-then-write race.
 *
 * Deliberately JdbcTemplate, not a JPA entity: one insert, no reads, no
 * relationships — an entity would be ceremony for a mailing list row.
 * Sits behind the per-IP auth rate limiter (AuthRateLimitFilter), which
 * bounds signup spam the same way it bounds login guessing.
 */
@RestController
@RequestMapping("/api/v1/newsletter")
public class NewsletterController {

    private final JdbcTemplate jdbc;

    public NewsletterController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SubscribeRequest(
            @NotBlank @Email @Size(max = 254) String email
    ) {}

    /**
     * @Transactional is LOAD-BEARING, not ceremony: this app runs Hikari
     * with auto-commit OFF (deliberate, see application.yml — pessimistic
     * locking semantics). A bare JdbcTemplate write outside a Spring
     * transaction executes, reports rowsAffected=1, and then silently
     * rolls back when the connection returns to the pool. Found the hard
     * way: the test saw two "successful" 202s and an empty table.
     */
    @Transactional
    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody SubscribeRequest request) {
        jdbc.update(
                "INSERT INTO newsletter_subscribers (email) VALUES (?) ON CONFLICT (email) DO NOTHING",
                request.email().trim().toLowerCase());
        return ResponseEntity.accepted().build();
    }
}
