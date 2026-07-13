package com.marketplace.api.storage;

import com.marketplace.api.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for a real production incident: a V11 deploy with a
 * (deliberately, in this test) malformed R2_ACCOUNT_ID crash-looped the
 * ENTIRE API, not just image upload, because ObjectStorageService took a
 * direct S3Client dependency and R2Config's @Bean-level @Lazy does not
 * defer construction when a normal (non-lazy) singleton's constructor
 * demands a concrete instance — Spring has to build it immediately to
 * satisfy that constructor either way.
 *
 * The fix (ObjectProvider<S3Client> in ObjectStorageService, deferring
 * resolution to first real use) is what this test locks in. If it
 * regresses, this test fails at context-load time — exactly the failure
 * mode that took the site down, reproduced safely in CI instead of prod.
 */
@Testcontainers
@SpringBootTest
class R2FaultIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");

        // Deliberately malformed — a space is illegal in a URI authority,
        // so R2Config's URI.create("https://" + accountId + "...") throws.
        // This is the exact failure class that crash-looped production.
        registry.add("app.storage.r2.account-id", () -> "not a valid host");
        registry.add("app.storage.r2.access-key-id", () -> "irrelevant");
        registry.add("app.storage.r2.secret-access-key", () -> "irrelevant");
    }

    @Autowired ProductService productService;
    @Autowired ObjectStorageService storage;

    @Test
    void appBoots_andNonStorageFeaturesWork_despiteBrokenR2Config() {
        // The context loading at all (no @SpringBootTest failure) is most
        // of the assertion. This confirms the rest of the app is usable.
        assertThat(productService.list(PageRequest.of(0, 5))).isNotNull();
    }

    @Test
    void onlyActualImageUpload_failsWithTheBrokenConfig() {
        // BeanCreationException, not the raw IllegalArgumentException — the
        // lazy S3Client is resolved via ObjectProvider.getObject() inside
        // put(), and Spring wraps whatever the factory method throws. The
        // important thing is WHEN this throws (on first use, not at boot).
        assertThatThrownBy(() ->
                storage.put("some/key.png", new ByteArrayInputStream("x".getBytes()), 1, "image/png"))
                .isInstanceOf(BeanCreationException.class)
                .hasMessageContaining("Illegal character in authority");
    }
}
