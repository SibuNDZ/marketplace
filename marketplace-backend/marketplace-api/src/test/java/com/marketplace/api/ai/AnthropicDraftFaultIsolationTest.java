package com.marketplace.api.ai;

import com.marketplace.api.entity.User;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.ProductService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The R2FaultIsolationTest lesson, applied to the second external provider.
 *
 * A V11 deploy with a malformed R2_ACCOUNT_ID once crash-looped the ENTIRE
 * API, because a normal singleton took a direct S3Client dependency and
 * @Bean-level @Lazy does not defer construction when a non-lazy constructor
 * demands a concrete instance. The same shape would be far worse here: the
 * listing drafter is a convenience on one vendor form, and an unset
 * ANTHROPIC_API_KEY must never be able to take down checkout.
 *
 * This boots with a BLANK key — the realistic failure, since it is what an
 * un-set Railway variable produces — and asserts the split: the site works,
 * only the draft endpoint fails.
 *
 * Deliberately does NOT mock ListingDraftModel. Mocking it would bypass the
 * ObjectProvider resolution that is the entire thing under test.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AnthropicDraftFaultIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");

        // The failure this test exists for: the variable was never set.
        registry.add("app.ai.api-key", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductService productService;
    @Autowired TestFixtures fixtures;
    @Autowired JwtService jwtService;

    @Test
    void appBoots_andTheSiteWorks_despiteMissingApiKey() {
        // The context loading at all is most of the assertion — if the
        // AnthropicClient bean were built eagerly, or injected directly rather
        // than through ObjectProvider, @SpringBootTest would fail right here
        // and every request would 503 in production.
        assertThat(productService.list(PageRequest.of(0, 5))).isNotNull();
    }

    @Test
    void catalogueBrowsing_isUnaffected() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("size", "5"))
                .andExpect(status().isOk());
    }

    @Test
    void onlyTheDraftEndpoint_failsWithTheMissingKey() throws Exception {
        User vendor = fixtures.vendor("isolation-vendor1");
        String token = jwtService.generateToken(vendor.getId(), vendor.getRole().name());

        // 500, not 502: a blank env var is OUR misconfiguration, not the
        // provider failing. Reporting it as a provider outage would send
        // someone to check Anthropic's status page over a Railway variable.
        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg",
                                "non-empty".getBytes()))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isInternalServerError());
    }
}
