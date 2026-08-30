package com.marketplace.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Newsletter signup: unauthenticated, always 202, idempotent per address.
 * Full-stack via TestRestTemplate so the permitAll wiring is pinned too —
 * a regression that gates this behind JWT would fail here, not in prod.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NewsletterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;

    private ResponseEntity<String> subscribe(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/v1/newsletter/subscribe",
                new HttpEntity<>(Map.of("email", email), headers), String.class);
    }

    @Test
    void subscribe_storesLowercased_andRepeatIsSilentlyIdempotent() {
        assertThat(subscribe("Inner.Circle@Example.com").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        // Same address, different casing: same row, same 202 — the endpoint
        // must not disclose that the address was already subscribed.
        assertThat(subscribe("inner.circle@example.com").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        assertThat(jdbc.queryForList("SELECT email FROM newsletter_subscribers", String.class))
                .containsExactly("inner.circle@example.com");
    }

    @Test
    void subscribe_rejectsNonEmail() {
        assertThat(subscribe("not-an-email").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
