package com.marketplace.api.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
 * IP resolution: clientIp() below, NOT getRemoteAddr() directly. Behind
 * Railway's proxy getRemoteAddr() is the proxy's address for every request,
 * which put THE ENTIRE PLATFORM in one bucket — the 11th auth request
 * globally returned 429, and the frontend showed it as a generic failure.
 * forward-headers-strategy=framework does NOT fix that: ForwardedHeaderFilter
 * rewrites scheme/host/port for URL building and never touches the remote
 * address (only the container-level `native` strategy does). So this filter
 * resolves the client itself from X-Forwarded-For.
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

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private final int capacity;
    private final int refillPerMinute;
    private final CorsOrigins allowedOrigins;
    private final Cache<String, Bucket> buckets;

    public AuthRateLimitFilter(
            @Value("${app.rate-limit.auth.capacity:10}") int capacity,
            @Value("${app.rate-limit.auth.refill-per-minute:10}") int refillPerMinute,
            CorsOrigins allowedOrigins) {
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
        this.allowedOrigins = allowedOrigins;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight is NEVER rate-limited. Two reasons, both load-bearing:
        //
        // 1. A 429 is not a successful preflight, so the browser discards it
        //    and never sends the real request. The client sees an opaque CORS
        //    TypeError, not the 429 — so the hand-stamped CORS headers below
        //    are useless on this path and the frontend cannot say "too many
        //    attempts". A registration failure surfaces as "Something went
        //    wrong" with no way to tell it from the server being down.
        // 2. Counting the preflight halves the real limit: every JSON POST is
        //    two requests, so capacity 10 meant 5 actual login attempts.
        //
        // Preflights carry no credentials, so exempting them gives an attacker
        // nothing — the POST that follows is still counted.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientIp = clientIp(request);
        Bucket bucket = buckets.get(clientIp, ip -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillPerMinute, Duration.ofMinutes(1))
                        .build())
                .build());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        // Log every rejection. Without this the limiter is INVISIBLE: it does
        // not touch the controller, so nothing downstream logs, and a week of
        // production logs can show zero errors while users are being turned
        // away. That silence is what made this filter's role in a registration
        // outage so expensive to find — the logs looked healthy.
        log.warn("Auth rate limit exceeded: {} {} from {} - returning 429",
                request.getMethod(), request.getRequestURI(), clientIp);

        // Same problem+json shape as everything else; Retry-After makes
        // well-behaved clients back off instead of hammering.
        //
        // CORS headers set BY HAND: this filter runs before the security
        // chain, whose CorsFilter would normally add them. Without these a
        // browser client sees an opaque CORS TypeError instead of the 429 —
        // the frontend can't show "too many attempts, retry in a minute".
        String origin = request.getHeader("Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin");
            response.setHeader("Access-Control-Expose-Headers", "X-Request-Id, Retry-After");
        }
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests",\
                "status":429,"detail":"Rate limit exceeded for authentication \
                endpoints. Retry after 60 seconds."}""");
    }

    /**
     * The IP a bucket is keyed on: the RIGHTMOST X-Forwarded-For entry,
     * falling back to getRemoteAddr() when the header is absent (local dev,
     * tests, direct connections).
     *
     * Rightmost, never leftmost: each proxy appends the address of the peer
     * it received the request from, so the rightmost entry was written by
     * OUR edge (Railway) about its own peer and is the only one a client
     * cannot smuggle in. Keying on the leftmost entry would let an attacker
     * mint a fresh bucket per request with a forged header, which is a
     * rate-limiter bypass. The trade-off: if another proxy (e.g. Cloudflare)
     * is ever put in front, the rightmost entry becomes that proxy's edge
     * address and its ranges must be handled here — degraded per-edge
     * buckets, not a platform-wide one.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        int lastComma = forwarded.lastIndexOf(',');
        String rightmost = (lastComma < 0 ? forwarded : forwarded.substring(lastComma + 1)).trim();
        return rightmost.isEmpty() ? request.getRemoteAddr() : rightmost;
    }
}
