package com.marketplace.api.payout;

import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.DocumentType;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import com.marketplace.api.entity.VerificationProviderResult;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorVerificationRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Split switch ON, Paystack stubbed: the banking save validates the
 * account and records OK / MISMATCH / UNAVAILABLE; the document number is
 * on the wire to the stub and nowhere else; the admin re-run opens a new
 * attempt without a document and reports a refusal instead of guessing.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccountValidationFlowTest {

    private static final String ACCOUNT = "1122336789";
    private static final String ID_NUMBER = "9001015009087";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer STUB;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> RESPONSE = new AtomicReference<>("{}");
    static final List<String> BODIES = new ArrayList<>();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/bank/validate", exchange -> {
                synchronized (BODIES) {
                    BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] out = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(STATUS.get(), out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            STUB.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final String OK = "{\"status\":true,\"data\":{\"verified\":true,\"verificationMessage\":\"Personal Account Verification successful\","
            + "\"accountHolderMatch\":true,\"accountOpen\":true,\"accountOpenForMoreThanThreeMonths\":true,\"accountAcceptsCredits\":true}}";
    static final String MISMATCH = "{\"status\":true,\"data\":{\"verified\":false,\"verificationMessage\":\"Account name does not match\","
            + "\"accountHolderMatch\":false,\"accountOpen\":true}}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.paystack.api-base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("app.paystack.split-enabled", () -> "true");
        registry.add("logging.level.com.marketplace.api", () -> "DEBUG");
    }

    @Autowired VendorVerificationRepository verifications;
    @Autowired UserRepository               userRepository;
    @Autowired TestFixtures                 fixtures;
    @Autowired MockMvc                      mockMvc;
    @Autowired JwtService                   jwtService;

    @BeforeEach
    void reset() {
        STATUS.set(200);
        RESPONSE.set(OK);
        synchronized (BODIES) { BODIES.clear(); }
    }

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String body(String holder, String docNumber) {
        return "{\"accountHolderName\":\"" + holder + "\",\"bankName\":\"Nedbank\","
                + "\"accountNumber\":\"" + ACCOUNT + "\",\"branchCode\":\"198765\","
                + "\"bankCode\":\"198765\",\"accountType\":\"CHEQUE\""
                + (docNumber == null ? "" : ",\"identity\":{\"type\":\"ID_NUMBER\",\"number\":\"" + docNumber + "\"}")
                + "}";
    }

    private String token(User u) {
        return "Bearer " + jwtService.generateToken(u.getId(), u.getRole().name());
    }

    private VendorVerification latest(User vendor) {
        return verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId()).orElseThrow();
    }

    @Test
    @DisplayName("OK: verified and holder match; the five flags and message land on the row; the number goes to Paystack only")
    void okMapping(CapturedOutput output) throws Exception {
        User vendor = fixtures.vendor(uniq("val-ok"));
        String response = mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Thandi Dlamini", ID_NUMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        VendorVerification row = latest(vendor);
        assertThat(row.getProviderResult()).isEqualTo(VerificationProviderResult.OK);
        assertThat(row.getAccountHolderMatch()).isTrue();
        assertThat(row.getAccountOpen()).isTrue();
        assertThat(row.getAccountOpen3m()).isTrue();
        assertThat(row.getAcceptsCredits()).isTrue();
        assertThat(row.getVerificationMessage()).contains("successful");
        assertThat(row.getDocumentLast4()).isEqualTo("9087");

        String sent;
        synchronized (BODIES) { sent = BODIES.get(0); }
        assertThat(sent).contains("\"account_number\":\"" + ACCOUNT + "\"")
                .contains("\"document_number\":\"" + ID_NUMBER + "\"")
                .contains("\"document_type\":\"identityNumber\"")
                .contains("\"account_type\":\"personal\"")
                .contains("\"account_name\":\"Thandi Dlamini\"");
        assertThat(response).doesNotContain(ID_NUMBER).doesNotContain(ACCOUNT);
        assertThat(output.getAll()).doesNotContain(ID_NUMBER).doesNotContain(ACCOUNT);
    }

    @Test
    @DisplayName("MISMATCH: the call succeeded but the holder did not match")
    void mismatchMapping() throws Exception {
        RESPONSE.set(MISMATCH);
        User vendor = fixtures.vendor(uniq("val-mismatch"));
        mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Wrong Name", ID_NUMBER)))
                .andExpect(status().isOk());
        VendorVerification row = latest(vendor);
        assertThat(row.getProviderResult()).isEqualTo(VerificationProviderResult.MISMATCH);
        assertThat(row.getAccountHolderMatch()).isFalse();
        assertThat(row.getAcceptsCredits()).isNull();
        assertThat(row.getVerificationMessage()).isEqualTo("Account name does not match");
    }

    @Test
    @DisplayName("UNAVAILABLE: Paystack down still saves the banking and the row")
    void unavailableStillSaves() throws Exception {
        STATUS.set(503);
        RESPONSE.set("{\"status\":false,\"message\":\"down\"}");
        User vendor = fixtures.vendor(uniq("val-down"));
        mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Thandi Dlamini", ID_NUMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banking.complete").value(true));
        VendorVerification row = latest(vendor);
        assertThat(row.getProviderResult()).isEqualTo(VerificationProviderResult.UNAVAILABLE);
        assertThat(row.getOutcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(userRepository.findById(vendor.getId()).orElseThrow().getBankCode()).isEqualTo("198765");
    }

    @Test
    @DisplayName("no document supplied: NOT_RUN and no call to Paystack")
    void noDocumentNoCall() throws Exception {
        User vendor = fixtures.vendor(uniq("val-nodoc"));
        mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Thandi Dlamini", null)))
                .andExpect(status().isOk());
        assertThat(latest(vendor).getProviderResult()).isEqualTo(VerificationProviderResult.NOT_RUN);
        synchronized (BODIES) { assertThat(BODIES).isEmpty(); }
    }

    @Test
    @DisplayName("admin re-run opens a new attempt without the document, and a refusal is reported, not recorded")
    void adminRecheck() throws Exception {
        RESPONSE.set(MISMATCH);
        User vendor = fixtures.vendor(uniq("val-recheck"));
        mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Thandi Dlamini", ID_NUMBER)))
                .andExpect(status().isOk());
        Long firstRow = latest(vendor).getId();
        User admin = fixtures.admin(uniq("val-admin"));

        // The name was fixed on Paystack's side (or the first run was a fluke): re-run says OK.
        RESPONSE.set(OK);
        synchronized (BODIES) { BODIES.clear(); }
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/recheck")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerResult").value("OK"))
                .andExpect(jsonPath("$.documentLast4").value("···9087"));
        VendorVerification rerun = latest(vendor);
        assertThat(rerun.getId()).isNotEqualTo(firstRow); // a new attempt, the old row untouched
        assertThat(rerun.getProviderResult()).isEqualTo(VerificationProviderResult.OK);
        assertThat(rerun.getDocumentType()).isEqualTo(DocumentType.ID_NUMBER);
        assertThat(rerun.getDocumentLast4()).isEqualTo("9087");
        assertThat(rerun.getOutcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(verifications.findById(firstRow).orElseThrow().getProviderResult())
                .isEqualTo(VerificationProviderResult.MISMATCH);
        String sent;
        synchronized (BODIES) { sent = BODIES.get(0); }
        assertThat(sent).doesNotContain("document_number").contains("\"document_type\":\"identityNumber\"");

        // Paystack refuses the document-less request: 409, nothing written.
        STATUS.set(400);
        RESPONSE.set("{\"status\":false,\"message\":\"document_number is required\"}");
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/recheck")
                        .header("Authorization", token(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Re-run not possible"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("resubmit")));
        assertThat(latest(vendor).getId()).isEqualTo(rerun.getId());
    }

    @Test
    @DisplayName("the fees endpoint reports the split switch")
    void feesReportSplit() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/fees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splitEnabled").value(true))
                .andExpect(jsonPath("$.sellingGateEnabled").value(false));
    }
}
