package com.marketplace.api.payout;

import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import com.marketplace.api.entity.VerificationProviderResult;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorVerificationRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.DisplayName;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin verification endpoints through HTTP: the pending list carries
 * masked banking only, approve is refused on a provider mismatch, decisions
 * are written once, and approval opens the selling gate for the vendor.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class VendorVerificationAdminTest {

    private static final String ACCOUNT_NUMBER = "1122336789";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.payouts.selling-gate-enabled", () -> "true");
    }

    @Autowired VendorPayoutSettingsService    settingsService;
    @Autowired VendorVerificationRepository   verifications;
    @Autowired UserRepository                 userRepository;
    @Autowired TestFixtures                   fixtures;
    @Autowired MockMvc                        mockMvc;
    @Autowired JwtService                     jwtService;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String adminToken() {
        User admin = fixtures.admin(uniq("verify-admin"));
        return "Bearer " + jwtService.generateToken(admin.getId(), admin.getRole().name());
    }

    /** A vendor who saved banking (which opens a PENDING NOT_RUN row) and accepted terms. */
    private User pendingVendor(String tag) {
        User vendor = fixtures.vendor(uniq(tag));
        settingsService.updateBanking(vendor.getId(), "Holder " + tag, "Nedbank",
                ACCOUNT_NUMBER, "198765", "198765", BankAccountType.CHEQUE);
        settingsService.acceptTerms(vendor.getId(), 1);
        return vendor;
    }

    private VendorVerification latest(User vendor) {
        return verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId()).orElseThrow();
    }

    @Test
    @DisplayName("the pending list names the vendor, masks the account and carries no accountNumber field")
    void pendingListIsMasked() throws Exception {
        User vendor = pendingVendor("pending-list");
        Product p = fixtures.productForVendor(uniq("First Listing"), uniq("SKU-VA"),
                new BigDecimal("120.00"), 3, vendor);

        String body = mockMvc.perform(get("/api/v1/admin/vendors/pending")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.vendorId == " + vendor.getId() + ")].accountLast4").value(hasItem("···6789")))
                .andExpect(jsonPath("$[?(@.vendorId == " + vendor.getId() + ")].providerResult").value(hasItem("NOT_RUN")))
                .andExpect(jsonPath("$[?(@.vendorId == " + vendor.getId() + ")].listings[0].id").value(hasItem(p.getId().intValue())))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("accountNumber").doesNotContain(ACCOUNT_NUMBER);
    }

    @Test
    @DisplayName("approve is refused with 409 when the provider result is MISMATCH, and the row stays PENDING")
    void approveRefusedOnMismatch() throws Exception {
        User vendor = pendingVendor("mismatch");
        VendorVerification row = latest(vendor);
        row.setProviderResult(VerificationProviderResult.MISMATCH);
        verifications.save(row);

        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Verification cannot be approved"));

        assertThat(latest(vendor).getOutcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(userRepository.findById(vendor.getId()).orElseThrow().getVendorApprovedAt()).isNull();
    }

    @Test
    @DisplayName("approve sets vendor_approved_at and the decision columns; a second approve changes nothing")
    void approveIsIdempotent() throws Exception {
        User vendor = pendingVendor("approve");
        assertThat(settingsService.status(vendor.getId()).sellable()).isFalse();

        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Listings look genuine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.vendorApprovedAt").isNotEmpty());

        VendorVerification decided = latest(vendor);
        assertThat(decided.getOutcome()).isEqualTo(VerificationOutcome.APPROVED);
        assertThat(decided.getVerifiedBy()).isNotNull();
        assertThat(decided.getVerifiedAt()).isNotNull();
        assertThat(decided.getNote()).isEqualTo("Listings look genuine");
        assertThat(settingsService.status(vendor.getId()).sellable()).isTrue();

        // Re-approve (different admin, different note): 200, same state, nothing rewritten.
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"second click\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.note").value("Listings look genuine"));
        VendorVerification again = latest(vendor);
        assertThat(again.getId()).isEqualTo(decided.getId());
        assertThat(again.getVerifiedBy()).isEqualTo(decided.getVerifiedBy());
        assertThat(again.getVerifiedAt()).isEqualTo(decided.getVerifiedAt());

        // And a reject after an approve is equally inert.
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/reject")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"changed my mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"));
        assertThat(userRepository.findById(vendor.getId()).orElseThrow().getVendorApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("reject needs a note, writes the decision, and leaves vendor_approved_at null")
    void rejectNeedsNote() throws Exception {
        User vendor = pendingVendor("reject");

        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/reject")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(latest(vendor).getOutcome()).isEqualTo(VerificationOutcome.PENDING);

        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/reject")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Holder name does not match the store\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.vendorApprovedAt").value(org.hamcrest.Matchers.nullValue()));

        assertThat(latest(vendor).getOutcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(userRepository.findById(vendor.getId()).orElseThrow().getVendorApprovedAt()).isNull();
        assertThat(settingsService.status(vendor.getId()).verificationState()).isEqualTo("REJECTED");

        // Not in the pending list any more.
        mockMvc.perform(get("/api/v1/admin/vendors/pending").header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.vendorId == " + vendor.getId() + ")]").isEmpty());
    }

    @Test
    @DisplayName("a vendor with no verification row is 404; a non-admin is refused")
    void notFoundAndForbidden() throws Exception {
        User bare = fixtures.vendor(uniq("no-row"));
        mockMvc.perform(post("/api/v1/admin/vendors/" + bare.getId() + "/approve")
                        .header("Authorization", adminToken()))
                .andExpect(status().isNotFound());

        String vendorToken = "Bearer " + jwtService.generateToken(bare.getId(), bare.getRole().name());
        mockMvc.perform(get("/api/v1/admin/vendors/pending").header("Authorization", vendorToken))
                .andExpect(status().isForbidden());
    }
}
