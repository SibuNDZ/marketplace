package com.marketplace.api.feedback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-user budget for feedback submission, mirroring DraftRateLimiter
 * (authenticated endpoint, so the account is the natural key; in-memory
 * Caffeine, correct for single-instance Railway).
 *
 * Unlike the drafter this endpoint costs nothing per call; 5/hour exists
 * purely to stop a runaway script or a paste-bomb from flooding the admin
 * inbox. No sincere human writes six pieces of feedback in an hour, so the
 * limit should be invisible to everyone it isn't aimed at.
 */
@Component
public class FeedbackRateLimiter {

    private final int perHour;
    private final Cache<Long, Bucket> buckets;

    FeedbackRateLimiter(@Value("${app.feedback.rate-limit.per-hour:5}") int perHour) {
        this.perHour = perHour;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                // Outlives the refill window so a bucket is never evicted
                // mid-hour and silently handed back a full allowance.
                .expireAfterAccess(Duration.ofHours(2))
                .build();
    }

    public void checkAndConsume(Long userId) {
        Bucket bucket = buckets.get(userId, id -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perHour)
                        .refillGreedy(perHour, Duration.ofHours(1))
                        .build())
                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1,
                    Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
            throw new FeedbackExceptions.FeedbackRateLimitExceededException(retryAfter);
        }
    }
}
