package com.marketplace.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ways around the auth rate limiter, through a real Tomcat configured the way
 * production is: forward-headers-strategy=framework (application-prod.yml),
 * which puts Spring's ForwardedHeaderFilter in front of AuthRateLimitFilter.
 *
 * Every request carries its own X-Forwarded-For whose rightmost entry stands
 * in for the one Railway's edge appends, so each test gets its own bucket
 * and the class does not depend on test order.
 *
 * Probes are GETs to a path that does not exist under /api/v1/auth/, as in
 * ObservabilityTest: the limiter counts them exactly like a login, without a
 * bcrypt check per request giving the bucket time to refill.
 * java.net.http.HttpClient rather than TestRestTemplate, which would
 * re-encode %61 as %2561 and test nothing.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
class AuthRateLimitBypassTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.rate-limit.auth.capacity", () -> "3");
    }

    private static final int CAPACITY = 3;
    private static final String PROBE = "/api/v1/auth/_rate-limit-probe";

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void forgedLeftmostForwardedFor_doesNotBuyAFreshBucket() throws Exception {
        // The client writes the left of X-Forwarded-For; the edge appends the
        // real peer on the right. ForwardedHeaderFilter answers getRemoteAddr()
        // with the LEFT entry and hides the header, so keying on what it
        // exposes gave every forged value its own bucket.
        for (int i = 1; i <= CAPACITY; i++) {
            assertThat(get(PROBE, "203.0.113." + i + ", 198.51.100.1", null)).isNotEqualTo(429);
        }
        assertThat(get(PROBE, "203.0.113.99, 198.51.100.1", null)).isEqualTo(429);
    }

    @Test
    void percentEncodedAuthPath_isStillLimited() throws Exception {
        // Spring decodes path segments before security matching and routing,
        // so /api/v1/%61uth/... is /api/v1/auth/... to everything except a
        // prefix test on the raw URI.
        String encoded = "/api/v1/%61uth/_rate-limit-probe";
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(get(encoded, "198.51.100.2", null)).isNotEqualTo(429);
        }
        assertThat(get(encoded, "198.51.100.2", null)).isEqualTo(429);
    }

    @Test
    void forgedForwardedPrefix_doesNotHideAnAuthPath() throws Exception {
        // ForwardedHeaderFilter turns X-Forwarded-Prefix into a context path:
        // getRequestURI() becomes /x/api/v1/auth/..., routing strips /x again.
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(get(PROBE, "198.51.100.3", "/x")).isNotEqualTo(429);
        }
        assertThat(get(PROBE, "198.51.100.3", "/x")).isEqualTo(429);
    }

    @Test
    void percentEncodedLoginPath_reachesTheLoginController() throws Exception {
        // Why the encoded form matters: it is not a 404, it is a real login
        // attempt with a real verdict, which is what the limiter exists to ration.
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/%61uth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "198.51.100.4")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
                .build();

        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Invalid email or password");
    }

    // --- helpers -----------------------------------------------------------

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private int get(String path, String forwardedFor, String forwardedPrefix) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("X-Forwarded-For", forwardedFor)
                .GET();
        if (forwardedPrefix != null) {
            builder.header("X-Forwarded-Prefix", forwardedPrefix);
        }
        return http.send(builder.build(), BodyHandlers.discarding()).statusCode();
    }
}
