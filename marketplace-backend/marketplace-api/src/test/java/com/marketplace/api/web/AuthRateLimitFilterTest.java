package com.marketplace.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the filter's client-IP resolution and per-client bucketing.
 * No Spring context and no Testcontainers on purpose: the proxy-IP bug this
 * guards against (every request behind Railway's edge sharing one bucket)
 * is invisible to the integration tests, which all connect from 127.0.0.1
 * with no X-Forwarded-For header. The end-to-end 429 behaviour stays covered
 * by ObservabilityTest, and the ways around it by AuthRateLimitBypassTest.
 */
class AuthRateLimitFilterTest {

    // ── clientIp resolution ──────────────────────────────────────────────

    @Test
    void noForwardedHeader_fallsBackToRemoteAddr() {
        assertThat(AuthRateLimitFilter.clientIp(request(null, "10.0.0.7")))
                .isEqualTo("10.0.0.7");
    }

    @Test
    void blankForwardedHeader_fallsBackToRemoteAddr() {
        assertThat(AuthRateLimitFilter.clientIp(request("   ", "10.0.0.7")))
                .isEqualTo("10.0.0.7");
    }

    @Test
    void singleForwardedEntry_isUsed() {
        assertThat(AuthRateLimitFilter.clientIp(request("203.0.113.9", "10.0.0.7")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void multipleForwardedEntries_rightmostWins() {
        // Leftmost entries are client-controlled: a forged header must not
        // let an attacker choose their own bucket key.
        assertThat(AuthRateLimitFilter.clientIp(
                request("6.6.6.6, 203.0.113.9", "10.0.0.7")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void forwardedEntriesAreTrimmed() {
        assertThat(AuthRateLimitFilter.clientIp(
                request("6.6.6.6,  203.0.113.9  ", "10.0.0.7")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void severalForwardedLines_theLastLineDecides() {
        // A proxy that adds its own line instead of extending the client's
        // leaves the forged line first, which is all getHeader() returns.
        MockHttpServletRequest request = request("6.6.6.6", "10.0.0.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(AuthRateLimitFilter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void beneathForwardedHeaderFilter_theOriginalHeaderStillDecides() throws Exception {
        // Production runs Spring's ForwardedHeaderFilter first. Pin what it
        // does to the request, since the fix exists because of it: the header
        // disappears and getRemoteAddr() becomes the forgeable leftmost entry.
        MockHttpServletRequest raw = request("6.6.6.6, 203.0.113.9", "10.0.0.7");
        raw.setRequestURI("/api/v1/auth/login");
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeaderFilter().doFilter(raw, new MockHttpServletResponse(), chain);
        HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();

        assertThat(wrapped.getHeader("X-Forwarded-For")).isNull();
        assertThat(wrapped.getRemoteAddr()).isEqualTo("6.6.6.6");
        assertThat(AuthRateLimitFilter.clientIp(wrapped)).isEqualTo("203.0.113.9");
    }

    @Test
    void trailingComma_fallsBackToRemoteAddr() {
        // A malformed header must not produce "" as a shared bucket key.
        assertThat(AuthRateLimitFilter.clientIp(request("203.0.113.9,", "10.0.0.7")))
                .isEqualTo("10.0.0.7");
    }

    // ── bucketing behaviour ──────────────────────────────────────────────

    @Test
    void distinctForwardedClients_getDistinctBuckets() throws Exception {
        // The production bug: same remoteAddr (the proxy) for everyone. Two
        // clients that differ only in X-Forwarded-For must not share a bucket.
        AuthRateLimitFilter filter = new AuthRateLimitFilter(2, 2,
                new CorsOrigins("http://localhost:5173"));

        assertThat(status(filter, "203.0.113.9")).isNotEqualTo(429);
        assertThat(status(filter, "203.0.113.9")).isNotEqualTo(429);
        assertThat(status(filter, "203.0.113.9")).isEqualTo(429);

        // A different client behind the same proxy is unaffected.
        assertThat(status(filter, "198.51.100.4")).isNotEqualTo(429);
    }

    // ── scope ────────────────────────────────────────────────────────────

    @Test
    void percentEncodedAuthPath_isInScope() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, 1,
                new CorsOrigins("http://localhost:5173"));

        assertThat(status(filter, "203.0.113.9", "", "/api/v1/%61uth/login")).isNotEqualTo(429);
        assertThat(status(filter, "203.0.113.9", "", "/api/v1/%61uth/login")).isEqualTo(429);
    }

    @Test
    void forwardedPrefixAsContextPath_isInScope() throws Exception {
        // What ForwardedHeaderFilter makes of X-Forwarded-Prefix: /x.
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, 1,
                new CorsOrigins("http://localhost:5173"));

        assertThat(status(filter, "203.0.113.9", "/x", "/x/api/v1/auth/login")).isNotEqualTo(429);
        assertThat(status(filter, "203.0.113.9", "/x", "/x/api/v1/newsletter/subscribe")).isEqualTo(429);
    }

    @Test
    void otherPaths_areNotLimited() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, 1,
                new CorsOrigins("http://localhost:5173"));

        for (int i = 0; i < 3; i++) {
            assertThat(status(filter, "203.0.113.9", "", "/api/v1/products")).isNotEqualTo(429);
        }
    }

    private static int status(AuthRateLimitFilter filter, String forwardedFor) throws Exception {
        return status(filter, forwardedFor, "", "/api/v1/auth/login");
    }

    private static int status(AuthRateLimitFilter filter, String forwardedFor,
                              String contextPath, String requestUri) throws Exception {
        MockHttpServletRequest request = request(forwardedFor, "10.0.0.7");
        request.setMethod("POST");
        request.setContextPath(contextPath);
        request.setRequestURI(requestUri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest request(String forwardedFor, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
