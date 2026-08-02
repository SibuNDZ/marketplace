package com.marketplace.api.feedback;

import com.marketplace.api.entity.PlatformFeedback;
import com.marketplace.api.repository.PlatformFeedbackRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.entity.User;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The feedback channel end to end through MockMvc: submission with rate
 * limiting, the admin inbox with its status filter, review idempotency, and
 * every auth boundary from the spec.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class FeedbackTest {

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

    @Autowired MockMvc                    mockMvc;
    @Autowired JwtService                 jwtService;
    @Autowired TestFixtures               fixtures;
    @Autowired PlatformFeedbackRepository feedbackRepository;

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    private String body(String category, String message) {
        return "{\"category\":\"" + category + "\",\"message\":\"" + message + "\"}";
    }

    @Test
    void submit_happyPath_persistsAsNew() throws Exception {
        User vendor = fixtures.vendor("fb-vendor1");

        mockMvc.perform(post("/api/v1/feedback")
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("IDEA", "Let vendors bulk-edit stock")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        assertThat(feedbackRepository.findAll())
                .anySatisfy(f -> {
                    assertThat(f.getMessage()).isEqualTo("Let vendors bulk-edit stock");
                    assertThat(f.getCategory()).isEqualTo(PlatformFeedback.Category.IDEA);
                    assertThat(f.getStatus()).isEqualTo(PlatformFeedback.Status.NEW);
                });
    }

    @Test
    void submit_buyerAllowed_notJustVendors() throws Exception {
        User buyer = fixtures.customer("fb-buyer1");
        mockMvc.perform(post("/api/v1/feedback")
                        .header("Authorization", "Bearer " + tokenFor(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PRAISE", "Checkout was painless")))
                .andExpect(status().isCreated());
    }

    @Test
    void submit_unauthenticated_401() throws Exception {
        mockMvc.perform(post("/api/v1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BUG", "anonymous shouting")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submit_overLength_400() throws Exception {
        User vendor = fixtures.vendor("fb-vendor2");
        mockMvc.perform(post("/api/v1/feedback")
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OTHER", "x".repeat(2001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_sixthWithinHour_429_withRetryAfter() throws Exception {
        User vendor = fixtures.vendor("fb-vendor3");
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/v1/feedback")
                            .header("Authorization", "Bearer " + tokenFor(vendor))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OTHER", "message " + i)))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/v1/feedback")
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OTHER", "message 6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void adminList_filtersByStatus_newestFirst() throws Exception {
        User admin = fixtures.admin("fb-admin1");
        User vendor = fixtures.vendor("fb-vendor4");

        mockMvc.perform(post("/api/v1/feedback")
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BUG", "filter me")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/feedback").param("status", "NEW")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.message == 'filter me')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.userEmail == 'fb-vendor4@test.local')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/feedback").param("status", "REVIEWED")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.message == 'filter me')]").isEmpty());
    }

    @Test
    void markReviewed_flipsStatus_andIsIdempotent() throws Exception {
        User admin = fixtures.admin("fb-admin2");
        User vendor = fixtures.vendor("fb-vendor5");

        String response = mockMvc.perform(post("/api/v1/feedback")
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("COMPLAINT", "review me twice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(response.replaceAll("\\D", ""));

        mockMvc.perform(post("/api/v1/admin/feedback/" + id + "/reviewed")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isNoContent());
        // Second review: still 204, not an error.
        mockMvc.perform(post("/api/v1/admin/feedback/" + id + "/reviewed")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isNoContent());

        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(PlatformFeedback.Status.REVIEWED);
    }

    @Test
    void adminRoutes_rejectNonAdmins_403() throws Exception {
        User vendor = fixtures.vendor("fb-vendor6");
        mockMvc.perform(get("/api/v1/admin/feedback")
                        .header("Authorization", "Bearer " + tokenFor(vendor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/feedback/1/reviewed")
                        .header("Authorization", "Bearer " + tokenFor(vendor)))
                .andExpect(status().isForbidden());
    }
}
