package com.marketplace.api.payout;

import com.marketplace.api.entity.DocumentType;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.repository.VendorVerificationRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /payouts/banking with an identity document, through HTTP: the saved
 * row holds the last four, the response holds nothing of the number, and
 * neither does anything logged during the request (including a validation
 * failure, which is where a framework likes to echo the rejected value).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class IdentityDocumentFlowTest {

    private static final String NUMBER = "9001015009087";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Capture everything the API logs during the request, not just INFO.
        registry.add("logging.level.com.marketplace.api", () -> "DEBUG");
    }

    @Autowired VendorVerificationRepository verifications;
    @Autowired TestFixtures                 fixtures;
    @Autowired MockMvc                      mockMvc;
    @Autowired JwtService                   jwtService;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String body(String accountNumber, String docNumber) {
        return "{\"accountHolderName\":\"Thandi Dlamini\",\"bankName\":\"Nedbank\","
                + "\"accountNumber\":\"" + accountNumber + "\",\"branchCode\":\"198765\","
                + "\"bankCode\":\"198765\",\"accountType\":\"CHEQUE\","
                + "\"identity\":{\"type\":\"ID_NUMBER\",\"number\":\"" + docNumber + "\"}}";
    }

    @Test
    @DisplayName("the row keeps the last four of the document; the number is in no response and no log line")
    void last4StoredNumberNowhereElse(CapturedOutput output) throws Exception {
        User vendor = fixtures.vendor(uniq("identity-vendor"));
        String token = "Bearer " + jwtService.generateToken(vendor.getId(), vendor.getRole().name());

        String response = mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1122336789", NUMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("PENDING"))
                .andExpect(jsonPath("$.banking.accountNumberLast4").value("···6789"))
                .andReturn().getResponse().getContentAsString();

        VendorVerification row = verifications
                .findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId()).orElseThrow();
        assertThat(row.getDocumentType()).isEqualTo(DocumentType.ID_NUMBER);
        assertThat(row.getDocumentLast4()).isEqualTo("9087");
        // Split switch off (test yml): no provider call, the row stays NOT_RUN.
        assertThat(row.getProviderResult())
                .isEqualTo(com.marketplace.api.entity.VerificationProviderResult.NOT_RUN);

        assertThat(response).doesNotContain(NUMBER).doesNotContain("1122336789");
        assertThat(output.getAll()).doesNotContain(NUMBER).doesNotContain("1122336789");

        // Same banking, same document again: no second row.
        mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1122336789", NUMBER)))
                .andExpect(status().isOk());
        assertThat(verifications.findAll().stream()
                .filter(v -> v.getVendor().getId().equals(vendor.getId())).count()).isEqualTo(1);

        // A different document on the same banking reopens verification.
        mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1122336789", "8801015009088")))
                .andExpect(status().isOk());
        assertThat(verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId())
                .orElseThrow().getDocumentLast4()).isEqualTo("9088");
        assertThat(output.getAll()).doesNotContain("8801015009088");
    }

    @Test
    @DisplayName("a rejected document number is echoed by neither the 400 body nor the log")
    void validationFailureDoesNotEcho(CapturedOutput output) throws Exception {
        User vendor = fixtures.vendor(uniq("identity-bad"));
        String token = "Bearer " + jwtService.generateToken(vendor.getId(), vendor.getRole().name());
        String bad = "900101500908712345678901234567890123456789"; // over 40 characters

        String response = mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1122336789", bad)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(bad);
        assertThat(output.getAll()).doesNotContain(bad);
        assertThat(verifications.findAll().stream()
                .filter(v -> v.getVendor().getId().equals(vendor.getId()))).isEmpty();
    }
}
