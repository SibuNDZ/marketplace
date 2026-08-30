package com.marketplace.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public fees contract as decided by the owner (2026-08-30): 10%
 * commission, presented as live. These literals are the decision written
 * down — if config drifts, this fails and someone must consciously decide
 * the new numbers, the same stance as CommissionLedgerTest's arithmetic.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicFeesTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // The test-classpath application.yml keeps 0.125 for the ledger
        // arithmetic; this test pins the PRODUCTION defaults instead, so it
        // fails if the decided public numbers ever drift.
        registry.add("app.payouts.commission-rate", () -> "0.10");
        registry.add("app.payouts.commission-confirmed", () -> "true");
    }

    @Autowired TestRestTemplate restTemplate;

    @Test
    void fees_areTheDecidedTenPercent_andPubliclyLive() {
        ResponseEntity<String> r = restTemplate.getForEntity("/api/v1/fees", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody())
                .contains("\"commissionLive\":true")
                .contains("\"commissionPercent\":\"10\"");
    }
}
