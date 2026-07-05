package com.marketplace.api.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-IP token bucket on the auth endpoints — the brute-force / enumeration
 * countermeasure. Scope is deliberately ONLY /api/v1/auth/**: login guessing
 * and register enumeration are the attacks. A global limiter is a self-
 * inflicted launch-day outage.
 *
 * Sizing defaults: capacity 10, refill 10/min per IP.
 *   Human forgets password: fine (10 tries, then a 1-min breather).
 *   Credential stuffing at 10/min/IP: uneconomical.
 *   NAT'd office: 10+ people logging in within 1 minute → retryable 429.
 *
 * State is in-memory (Caffeine, 1h expiry, 100k IP cap ~ a few MB).
 * Correct for single-instance Railway. Horizontal scaling: N instances =
 * N independent buckets = effective limit × N. That's degraded, not broken.
 * Swap to bucket4j-redis when horizontal scaling actually happens.
 *
 * IP resolution: HttpServletRequest.getRemoteAddr(). Behind Railway's proxy
 * this is only correct once server.forward-headers-strategy=framework is set
 * in prod application-prod.yml. Without it every request appears to come from
 * the proxy and THE ENTIRE PLATFORM shares one bucket — the 11th login attempt
 * globally returns 429. See application-prod.yml for the required setting.
 *
 * Runs AFTER CorrelationIdFilter (Order.HIGHEST_PRECEDENCE+1) so 429s carry
 * a requestId, and BEFORE Spring Security so rejected requests never burn
 * bcrypt cycles.
 *
 * Do NOT also add this filter via addFilterBefore in SecurityConfig —
 * @Component + @Order already places it. Double registration's symptom
 * (two tokens consumed per request) is subtle and wastes an afternoon.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final int capacity;
    private final int refillPerMinute;
    private final Cache<String, Bucket> buckets;

    public AuthRateLimitFilter(
            @Value("${app.rate-limit.auth.capacity:10}") int capacity,
            @Value("${app.rate-limit.auth.refill-per-minute:10}") int refillPerMinute) {
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillPerMinute, Duration.ofMinutes(1))
                        .build())
                .build());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        // Same problem+json shape as everything else; Retry-After makes
        // well-behaved clients back off instead of hammering.
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests",\
                "status":429,"detail":"Rate limit exceeded for authentication \
                endpoints. Retry after 60 seconds."}""");
    }
}
