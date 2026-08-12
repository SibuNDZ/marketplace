package com.marketplace.api.discovery;

import com.marketplace.api.entity.Product;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a silent write failure that reached production.
 *
 * This datasource runs with hikari auto-commit: false. A JdbcTemplate write
 * outside a Spring-managed transaction executes, reports a row count, and is
 * then discarded when the connection returns to the pool — no exception. The
 * embedding sweep logged "Embedded 12 product(s)" while the table stayed
 * empty, so every row looked stale forever and was re-embedded on each run:
 * a silent, recurring bill for work that was thrown away.
 *
 * The test deliberately does NOT run inside a transaction of its own. That is
 * the entire point: a @Transactional test would see the uncommitted write and
 * pass while production still broke. Reading back on a fresh connection is
 * what proves the data was actually committed.
 */
@Testcontainers
@SpringBootTest
class ProductEmbeddingPersistenceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    @Autowired ProductEmbeddingRepository embeddings;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @Test
    void embeddingSurvivesTheConnectionReturningToThePool() {
        Product product = fixtures.product(
                "Embedding Probe", "SKU-EMB-1", new BigDecimal("10"), 5);

        double[] vector = {0.1, 0.2, 0.3};
        embeddings.save(product.getId(), "hash-abc", vector);

        // Fresh read. Before the @Transactional fix this came back null even
        // though save() had returned normally.
        double[] stored = embeddings.embeddingOf(product.getId());
        assertThat(stored).as("vector must be committed, not just executed")
                .isNotNull()
                .containsExactly(0.1, 0.2, 0.3);

        String hash = jdbc.queryForObject(
                "SELECT embedding_hash FROM products WHERE id = ?", String.class, product.getId());
        assertThat(hash).isEqualTo("hash-abc");
    }

    @Test
    void reEmbeddingStopsOnceTheHashMatches() {
        Product product = fixtures.product(
                "Stale Probe", "SKU-EMB-2", new BigDecimal("10"), 5);

        // The sweep's own staleness query decides what gets re-embedded, and
        // it hashes in SQL. Saving that exact hash must take the row out of
        // the work list — otherwise the job pays to embed it again forever,
        // which is the second half of the production bug.
        String textHash = jdbc.queryForObject("""
                SELECT encode(sha256(convert_to(
                           name || ' ' || COALESCE(array_to_string(tags, ' '), '')
                                || ' ' || COALESCE(description, ''), 'UTF8')), 'hex')
                FROM products WHERE id = ?
                """, String.class, product.getId());

        assertThat(embeddings.findStale(50))
                .as("a product with no embedding is stale")
                .anyMatch(p -> p.productId() == product.getId());

        embeddings.save(product.getId(), textHash, new double[]{1.0, 0.0});

        assertThat(embeddings.findStale(50))
                .as("once embedded with the matching hash it must drop out of the work list")
                .noneMatch(p -> p.productId() == product.getId());
    }
}
