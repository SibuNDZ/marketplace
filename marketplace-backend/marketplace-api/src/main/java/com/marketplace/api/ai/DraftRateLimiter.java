package com.marketplace.api.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-VENDOR budget for the listing drafter.
 *
 * Keyed on the authenticated user id, NOT the client IP — deliberately unlike
 * AuthRateLimitFilter. That limiter defends an unauthenticated endpoint where
 * IP is the only identity available, and it pays for that with the
 * carrier-NAT problem (a whole mobile network shares one bucket). Here the
 * caller is always authenticated, so the natural unit is the account that
 * will be billed for the calls. It is also the fairer one: two vendors behind
 * the same office NAT get their own budgets.
 *
 * This is NOT a brute-force defence. Every call spends real money at the
 * provider, so the limit exists to bound spend and to stop one vendor's
 * runaway script from consuming the month's budget.
 *
 * Enforced at the service/controller layer rather than as a servlet filter
 * because the identity it keys on does not exist until Spring Security has
 * authenticated the request — a HIGHEST_PRECEDENCE filter like the auth
 * limiter runs too early to see a principal at all.
 *
 * State is in-memory (Caffeine), same trade-off AuthRateLimitFilter documents:
 * correct for single-instance Railway, N instances means N budgets. Swap to
 * bucket4j-redis when horizontal scaling actually happens.
 */
@Component
public class DraftRateLimiter {

    private final int perHour;
    private final Cache<Long, Bucket> buckets;

    DraftRateLimiter(@Value("${app.ai.rate-limit.per-hour:10}") int perHour) {
        this.perHour = perHour;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                // Longer than the refill window so a bucket is not evicted
                // mid-hour and silently handed back a full allowance.
                .expireAfterAccess(Duration.ofHours(2))
                .build();
    }

    /**
     * @throws DraftExceptions.DraftRateLimitExceededException carrying the
     *         seconds until the next token, so the response can set a truthful
     *         Retry-After rather than a guessed constant.
     */
    public void checkAndConsume(Long userId) {
        Bucket bucket = buckets.get(userId, id -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        // Capacity IS the hourly allowance, so the 11th call
                        // within an hour is the one that 429s.
                        //
                        // NOTE — the spec said "10 requests/hour, burst 3",
                        // and under bucket4j those two cannot both hold. A
                        // capacity of 3 makes the FOURTH rapid call fail, not
                        // the eleventh, which contradicts the required test
                        // (draft_rateLimit_11thCallInHour_429). The test is
                        // the more precise statement of intent, so capacity
                        // follows it. The cost of this choice is that a vendor
                        // may burst all 10 at once rather than being held to 3.
                        // To cap burst instead, add a second Bandwidth of
                        // capacity 3 refilling per minute — and expect the
                        // fourth call, not the eleventh, to be the 429.
                        .capacity(perHour)
                        // Greedy refill spreads the allowance smoothly rather
                        // than releasing 10 at the top of each hour, so a
                        // vendor drafting steadily is never blocked while a
                        // script burning the budget in one go still is.
                        .refillGreedy(perHour, Duration.ofHours(1))
                        .build())
                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1,
                    Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
            throw new DraftExceptions.DraftRateLimitExceededException(retryAfter);
        }
    }
}
