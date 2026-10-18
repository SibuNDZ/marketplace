package com.marketplace.api.payout;

import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorVerificationRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Split switch ON with a stubbed /subaccount: approve creates the
 * subaccount in the same unit as the decision (failure leaves the row
 * PENDING and approval null), a banking change on a subaccounted vendor
 * updates it, the gate needs the code, and the admin status read is on
 * demand.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SubaccountOnApproveTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer STUB;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    static final List<String> REQUESTS = new ArrayList<>(); // "METHOD path body"

    static final String CREATED = "{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_new1\",\"is_verified\":false,\"settlement_schedule\":\"AUTO\"}}";

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/subaccount", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (REQUESTS) {
                    REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.paystack.api-base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("app.paystack.split-enabled", () -> "true");
        registry.add("app.payouts.selling-gate-enabled", () -> "true");
    }

    @Autowired VendorPayoutSettingsService  settingsService;
    @Autowired VendorVerificationRepository verifications;
    @Autowired UserRepository               userRepository;
    @Autowired OrderService                 orderService;
    @Autowired SellingGate                  gate;
    @Autowired TestFixtures                 fixtures;
    @Autowired MockMvc                      mockMvc;
    @Autowired JwtService                   jwtService;

    @BeforeEach
    void reset() {
        STATUS.set(200);
        RESPONSE.set(CREATED);
        synchronized (REQUESTS) { REQUESTS.clear(); }
    }

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String token(User u) {
        return "Bearer " + jwtService.generateToken(u.getId(), u.getRole().name());
    }

    /** Banking (no document: NOT_RUN, approvable) and terms; a PENDING row exists. */
    private User pendingVendor(String tag) {
        User vendor = fixtures.vendor(uniq(tag));
        vendor.setBusinessName("Store " + tag);
        userRepository.save(vendor);
        settingsService.updateBanking(vendor.getId(), "Holder " + tag, "Nedbank",
                "1122336789", "198765", "198765", BankAccountType.CHEQUE);
        settingsService.acceptTerms(vendor.getId(), 1);
        return vendor;
    }

    private VendorVerification latest(User vendor) {
        return verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId()).orElseThrow();
    }

    private String firstRequest() {
        synchronized (REQUESTS) { return REQUESTS.get(0); }
    }

    @Test
    @DisplayName("approve creates the subaccount with the spec's fields and stores the code; the gate then opens")
    void approveCreatesSubaccount() throws Exception {
        User vendor = pendingVendor("sub-ok");
        User admin = fixtures.admin(uniq("sub-admin"));
        assertThat(gate.sellable(userRepository.findById(vendor.getId()).orElseThrow())).isFalse();

        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.subaccountCode").value("ACCT_new1"));

        User approved = userRepository.findById(vendor.getId()).orElseThrow();
        assertThat(approved.getPaystackSubaccountCode()).isEqualTo("ACCT_new1");
        assertThat(approved.getVendorApprovedAt()).isNotNull();
        assertThat(gate.sellable(approved)).isTrue();

        String sent = firstRequest();
        assertThat(sent).startsWith("POST /subaccount ");
        assertThat(sent).contains("\"business_name\":\"Store sub-ok")
                .contains("\"bank_code\":\"198765\"")
                .contains("\"account_number\":\"1122336789\"")
                .contains("\"percentage_charge\":10")
                .contains("\"primary_contact_email\":\"" + approved.getEmail() + "\"")
                .contains("\"description\":\"eRestyu vendor " + vendor.getId() + "\"")
                .contains("\"vendorId\":" + vendor.getId());
    }

    @Test
    @DisplayName("Paystack rejecting the subaccount is 409, Paystack down is 502; either way the row stays PENDING and approval null")
    void approveFailsCleanly() throws Exception {
        User vendor = pendingVendor("sub-fail");
        User admin = fixtures.admin(uniq("sub-admin2"));

        STATUS.set(400);
        RESPONSE.set("{\"status\":false,\"message\":\"Invalid bank code\"}");
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Verification cannot be approved"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Invalid bank code")));

        STATUS.set(503);
        RESPONSE.set("{\"status\":false,\"message\":\"down\"}");
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payments:provider-unavailable"));

        VendorVerification row = latest(vendor);
        assertThat(row.getOutcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(row.getVerifiedAt()).isNull();
        User user = userRepository.findById(vendor.getId()).orElseThrow();
        assertThat(user.getVendorApprovedAt()).isNull();
        assertThat(user.getPaystackSubaccountCode()).isNull();
    }

    @Test
    @DisplayName("a banking change on a subaccounted vendor updates the subaccount; a failed update is noted and re-attempted on approve")
    void bankingChangeUpdatesSubaccount() throws Exception {
        User vendor = pendingVendor("sub-upd");
        User admin = fixtures.admin(uniq("sub-admin3"));
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk());
        synchronized (REQUESTS) { REQUESTS.clear(); }

        // Update succeeds: PUT /subaccount/ACCT_new1 with the new number, approval cleared.
        RESPONSE.set("{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_new1\",\"is_verified\":false}}");
        settingsService.updateBanking(vendor.getId(), "Holder sub-upd", "Nedbank",
                "9988776655", "198765", "198765", BankAccountType.CHEQUE);
        String sent = firstRequest();
        assertThat(sent).startsWith("PUT /subaccount/ACCT_new1 ");
        assertThat(sent).contains("\"account_number\":\"9988776655\"");
        User user = userRepository.findById(vendor.getId()).orElseThrow();
        assertThat(user.getVendorApprovedAt()).isNull();
        assertThat(user.getPaystackSubaccountCode()).isEqualTo("ACCT_new1"); // still one subaccount
        assertThat(latest(vendor).getNote()).isNull();

        // Update fails: banking still saves, approval cleared, the row says so.
        synchronized (REQUESTS) { REQUESTS.clear(); }
        STATUS.set(503);
        RESPONSE.set("{\"status\":false,\"message\":\"down\"}");
        settingsService.updateBanking(vendor.getId(), "Holder sub-upd", "Nedbank",
                "5566778899", "198765", "198765", BankAccountType.CHEQUE);
        assertThat(userRepository.findById(vendor.getId()).orElseThrow().getAccountNumber()).isEqualTo("5566778899");
        assertThat(latest(vendor).getNote()).contains("Subaccount update failed");

        // The next approve re-attempts the UPDATE, never a second create.
        synchronized (REQUESTS) { REQUESTS.clear(); }
        STATUS.set(200);
        RESPONSE.set("{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_new1\",\"is_verified\":false}}");
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subaccountCode").value("ACCT_new1"));
        assertThat(firstRequest()).startsWith("PUT /subaccount/ACCT_new1 ");
        assertThat(firstRequest()).contains("\"account_number\":\"5566778899\"");
    }

    @Test
    @DisplayName("the gate with the split on refuses an approved vendor without a subaccount code")
    void gateNeedsCode() {
        User vendor = pendingVendor("sub-gate");
        User u = userRepository.findById(vendor.getId()).orElseThrow();
        u.setVendorApprovedAt(java.time.LocalDateTime.now());
        u.setPaystackSubaccountCode(null);
        assertThat(gate.sellable(u)).isFalse();
        u.setPaystackSubaccountCode("ACCT_x");
        assertThat(gate.sellable(u)).isTrue();

        Product p = fixtures.productForVendor(uniq("Gate Item"), uniq("SKU-SG"), new BigDecimal("50.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("gate-buyer"), p);
        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(PayoutExceptions.VendorNotSellableException.class);
    }

    @Test
    @DisplayName("subaccount status is read on demand and reports the dashboard verification flag")
    void statusOnDemand() throws Exception {
        User vendor = pendingVendor("sub-status");
        User admin = fixtures.admin(uniq("sub-admin4"));
        mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk());
        synchronized (REQUESTS) { REQUESTS.clear(); }

        RESPONSE.set("{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_new1\",\"is_verified\":true,\"settlement_schedule\":\"AUTO\"}}");
        mockMvc.perform(get("/api/v1/admin/vendors/" + vendor.getId() + "/subaccount")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splitEnabled").value(true))
                .andExpect(jsonPath("$.code").value("ACCT_new1"))
                .andExpect(jsonPath("$.isVerified").value(true))
                .andExpect(jsonPath("$.settlementSchedule").value("AUTO"));
        assertThat(firstRequest()).startsWith("GET /subaccount/ACCT_new1 ");

        // The approved list names the vendor with the code; the pending list no longer does.
        mockMvc.perform(get("/api/v1/admin/vendors/approved").header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.vendorId == " + vendor.getId() + ")].subaccountCode")
                        .value(org.hamcrest.Matchers.hasItem("ACCT_new1")));
    }

    @Test
    @DisplayName("the admin PUT path sends no account number back and the responses never carry it")
    void responsesStayMasked() throws Exception {
        User vendor = pendingVendor("sub-mask");
        User admin = fixtures.admin(uniq("sub-admin5"));
        String body = mockMvc.perform(post("/api/v1/admin/vendors/" + vendor.getId() + "/approve")
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("1122336789");
        String vendorBody = mockMvc.perform(put("/api/v1/vendor/settings/payouts/banking")
                        .header("Authorization", token(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountHolderName\":\"Holder sub-mask\",\"bankName\":\"Nedbank\",\"accountNumber\":\"1122336789\","
                                + "\"branchCode\":\"198765\",\"bankCode\":\"198765\",\"accountType\":\"CHEQUE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(vendorBody).doesNotContain("1122336789");
    }
}
