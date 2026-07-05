package com.marketplace.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request a correlation id, carried three places at once:
 *  - MDC "requestId"  → appears on every log line the request produces
 *  - X-Request-Id response header → the user/frontend can quote it
 *  - GlobalExceptionHandler copies it into every ProblemDetail body
 *
 * The support loop collapses to one step: "send me the requestId from the
 * error response", then one log search returns every line that request touched —
 * including MANUAL REFUND REQUIRED, Stripe webhook events, expiry-job actions
 * (jobs mint their own MDC id — see OrderExpiryJob).
 *
 * Incoming X-Request-Id is honored if present (frontend or Railway edge may
 * already assign one) but hard-sanitized against an allowlist: the value
 * flows into logs and response headers, making it a log-injection / header-
 * splitting vector if accepted verbatim. Anything not matching the allowlist
 * is silently replaced with a fresh UUID.
 *
 * HIGHEST_PRECEDENCE: must wrap everything — security chain, rate limiter —
 * so 401s and 429s carry a requestId too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER  = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Allowlist: UUID-shaped, opaque token, or similar safe id formats. */
    private static final String SAFE = "[A-Za-z0-9\\-_.]{8,64}";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming != null && incoming.matches(SAFE))
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // container threads are pooled; a leaked MDC value
                                 // would stamp the next request with a wrong id
        }
    }
}
