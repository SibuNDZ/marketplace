package com.marketplace.api.ai;

import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The vendor listing drafter.
 *
 * ListingDraftModel is mocked at the seam — no test here touches the real
 * Anthropic API, spends money, or needs a key. That is also what makes the
 * interesting branches testable at all: a live model will not reliably return
 * malformed JSON or an invented category slug on demand, and those are exactly
 * the paths most likely to break.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ListingDraftTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Non-blank so AnthropicConfig's bean builds; the model itself is
        // mocked, so this key is never used to make a call.
        registry.add("app.ai.api-key", () -> "test-key-never-used");
    }

    @MockBean ListingDraftModel model;

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JwtService jwtService;
    @Autowired ProductRepository productRepository;

    private static final String VALID_JSON = """
            {"name":"Karoo wildflower honey",
             "description":"Raw honey in a glass jar with a handwritten label.",
             "categorySlug":"preserves-and-honey"}
            """;

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "product.jpg", "image/jpeg",
                "not-a-real-jpeg-but-non-empty".getBytes());
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    @Test
    void draft_happyPath_returnsDraft_nothingPersisted() throws Exception {
        User vendor = fixtures.vendor("draft-vendor1");
        given(model.draft(any(), any(), any())).willReturn(VALID_JSON);

        long before = productRepository.count();

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Karoo wildflower honey"))
                .andExpect(jsonPath("$.categorySlug").value("preserves-and-honey"))
                .andExpect(jsonPath("$.disclaimer").exists())
                // The whole safety model in one assertion: drafting suggests,
                // it never creates.
                .andExpect(jsonPath("$.id").doesNotExist());

        assertThat(productRepository.count()).isEqualTo(before);
    }

    /**
     * Proves the shared validator is actually shared — a PDF is rejected here
     * by the same ImageValidation the upload path calls, not by a second copy
     * of the rules that could drift.
     */
    @Test
    void draft_invalidImage_400() throws Exception {
        User vendor = fixtures.vendor("draft-vendor2");

        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf",
                "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(pdf)
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported image"));
    }

    @Test
    void draft_unknownCategoryFromModel_fallsBackToOther() throws Exception {
        User vendor = fixtures.vendor("draft-vendor3");
        given(model.draft(any(), any(), any())).willReturn("""
                {"name":"Something","description":"A thing.","categorySlug":"artisanal-widgets"}
                """);

        // A hallucinated slug must not 404 the vendor's request — they did
        // nothing wrong, and they are going to review the category anyway.
        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorySlug").value("other"));
    }

    @Test
    void draft_nonJsonModelOutput_502_cleanProblem() throws Exception {
        User vendor = fixtures.vendor("draft-vendor4");
        given(model.draft(any(), any(), any()))
                .willReturn("Sure! Here's a lovely listing for your honey.");

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Drafting unavailable"))
                // The raw model text is untrusted third-party output — it goes
                // to the log, never to the browser.
                .andExpect(jsonPath("$.detail").value(
                        "Drafting service returned an unusable response — try again"));
    }

    /** Fenced JSON is still usable — the prompt forbids fences, models add them anyway. */
    @Test
    void draft_markdownFencedJson_isStillParsed() throws Exception {
        User vendor = fixtures.vendor("draft-vendor5");
        given(model.draft(any(), any(), any())).willReturn(
                "```json\n" + VALID_JSON + "\n```");

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Karoo wildflower honey"));
    }

    @Test
    void draft_rateLimit_11thCallInHour_429() throws Exception {
        User vendor = fixtures.vendor("draft-vendor6");
        given(model.draft(any(), any(), any())).willReturn(VALID_JSON);
        String token = tokenFor(vendor);

        // The hourly allowance is 10; the 11th is the one that must fail.
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                            .file(photo())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many requests"));
    }

    /** The budget is per vendor, so one vendor's spend cannot block another. */
    @Test
    void draft_rateLimitIsPerVendor_notGlobal() throws Exception {
        User spender = fixtures.vendor("draft-vendor7");
        User bystander = fixtures.vendor("draft-vendor8");
        given(model.draft(any(), any(), any())).willReturn(VALID_JSON);

        String spenderToken = tokenFor(spender);
        for (int i = 1; i <= 11; i++) {
            mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                    .file(photo())
                    .header("Authorization", "Bearer " + spenderToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA));
        }

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + tokenFor(bystander))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk());
    }

    @Test
    void draft_asCustomerRole_403() throws Exception {
        User customer = fixtures.customer("draft-customer1");

        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isForbidden());
    }

    @Test
    void draft_unauthenticated_401() throws Exception {
        mockMvc.perform(multipart("/api/v1/vendor/products/draft")
                        .file(photo())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());
    }
}
