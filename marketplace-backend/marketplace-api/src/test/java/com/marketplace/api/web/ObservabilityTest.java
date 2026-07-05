package com.marketplace.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the observability slice:
 * correlation IDs, requestId on errors, and auth rate limiting.
 *
 * Rate-limit isolation: all tests share 127.0.0.1. Capacity is overridden to
 * 3 via @DynamicPropertySource. Only the rate-limit test uses /api/v1/auth/**,
 * so there is no cross-test token contamination.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Reduce capacity so the rate-limit test needs only 4 requests rather
        // than 11, and isolation from other tests is straightforward.
        registry.add("app.rate-limit.auth.capacity", () -> "3");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void responseCarriesRequestId() {
        ResponseEntity<String> r = restTemplate.getForEntity("/api/v1/products", String.class);
        assertThat(r.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void incomingRequestIdEchoed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER, "my-test-id-12345");
        ResponseEntity<String> r = restTemplate.exchange(
                "/api/v1/products", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(r.getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .isEqualTo("my-test-id-12345");
    }

    @Test
    void malformedIncomingIdReplaced() {
        // "evil!header!injection" contains '!' which is outside the allowlist
        // [A-Za-z0-9\-_.]; verifies the log-injection / header-splitting guard.
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER, "evil!header!injection");
        ResponseEntity<String> r = restTemplate.exchange(
                "/api/v1/products", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String returned = r.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(returned).isNotEqualTo("evil!header!injection");
        assertThat(returned).doesNotContain("!");
        assertThat(returned).isNotBlank();
    }

    @Test
    void errorBodyCarriesRequestId() {
        // GET /api/v1/products/999999 → 404 via GlobalExceptionHandler.
        // The problem body must carry requestId matching the response header.
        ResponseEntity<String> r = restTemplate.getForEntity(
                "/api/v1/products/999999", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        String requestIdHeader = r.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(requestIdHeader).isNotBlank();
        assertThat(r.getBody()).contains("requestId");
        assertThat(r.getBody()).contains(requestIdHeader);
    }

    @Test
    void authRateLimit_kicksInOnExceed() {
        // Use GET to a non-existent auth path: the filter fires (path matches
        // /api/v1/auth/**), a token is consumed, and the server returns 404
        // immediately (no DB, no bcrypt). Fast requests mean the bucket cannot
        // refill between them, so the (capacity+1)th request gets 429.
        // POST /api/v1/auth/login was tried but takes ~15s per call (bcrypt +
        // DB round-trip), giving the bucket time to refill between requests.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity(
                    "/api/v1/auth/_rate-limit-probe", String.class);
            assertThat(r.getStatusCode().value()).isNotEqualTo(429);
        }
        ResponseEntity<String> limited = restTemplate.getForEntity(
                "/api/v1/auth/_rate-limit-probe", String.class);
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void rateLimitScopedToAuthOnly() {
        // capacity=3; if limiter applied globally, the 4th request would 429.
        // All product GETs must succeed regardless.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity(
                    "/api/v1/products", String.class);
            assertThat(r.getStatusCode().value()).isNotEqualTo(429);
        }
    }
}
