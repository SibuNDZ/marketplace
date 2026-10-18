package com.marketplace.api.payment;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.SettlementMode;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.payout.PayoutAdminService;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.OrderService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * charge.success with data.split: the entries of vendors named in the split
 * are PAID / SPLIT with the Paystack reference in the same transaction as
 * the ledger write; vendors not in the split stay PENDING / EFT; a split
 * order whose webhook carries no split logs SPLIT MISSING ON CHARGE.SUCCESS
 * and marks nothing; a share off by more than a cent is marked and logged.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PaystackSplitWebhookTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.paystack.split-enabled", () -> "true");
        registry.add("app.payouts.commission-rate", () -> "0.125");
    }

    static final String KEY = PaystackSignatureTest.KEY;

    @Autowired MockMvc                      mockMvc;
    @Autowired OrderService                 orderService;
    @Autowired OrderRepository              orderRepository;
    @Autowired UserRepository               userRepository;
    @Autowired VendorPayoutEntryRepository  entryRepository;
    @Autowired PayoutAdminService           payoutAdminService;
    @Autowired TestFixtures                 fixtures;
    @Autowired JwtService                   jwtService;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private User vendorWith(String tag, String code) {
        User v = fixtures.vendor(uniq(tag));
        v.setPaystackSubaccountCode(code);
        v.setBusinessName("Store " + tag);
        return userRepository.save(v);
    }

    /** Two-vendor order: A (R100, ACCT_a) and B (R50, no subaccount). Split request recorded as sent. */
    private record Placed(Long orderId, User a, User b) {}

    private Placed splitOrder(String tag, boolean recordSplitRequest) {
        User a = vendorWith(tag + "-a", "ACCT_" + tag + "a");
        User b = vendorWith(tag + "-b", null);
        Product pa = fixtures.productForVendor(uniq("A"), uniq("SKU-WA"), new BigDecimal("100.00"), 5, a);
        Product pb = fixtures.productForVendor(uniq("B"), uniq("SKU-WB"), new BigDecimal("50.00"), 5, b);
        User buyer = fixtures.customerWithCartOf(uniq("w-buyer"), pa, pb);
        OrderResponse placed = orderService.placeOrder(buyer.getId());
        if (recordSplitRequest) {
            Order o = orderRepository.findById(placed.id()).orElseThrow();
            o.setSplitRequest("{\"type\":\"flat\",\"bearer_type\":\"account\",\"subaccounts\":[{\"subaccount\":\"ACCT_"
                    + tag + "a\",\"share\":8750}],\"reference\":\"ERY-" + placed.id() + "-SPLIT\"}");
            orderRepository.save(o);
        }
        return new Placed(placed.id(), a, b);
    }

    private static String body(Long orderId, String reference, long cents, String splitJson) {
        return "{\"event\":\"charge.success\",\"data\":{"
                + "\"id\":" + (5_000_000_000L + orderId) + ",\"domain\":\"test\",\"status\":\"success\","
                + "\"reference\":\"" + reference + "\",\"amount\":" + cents + ",\"currency\":\"ZAR\","
                + "\"metadata\":{\"orderId\":\"" + orderId + "\"}"
                + (splitJson == null ? "" : ",\"split\":" + splitJson)
                + "}}";
    }

    /** Paystack's documented shape: subaccounts[].subaccount is an object carrying subaccount_code. */
    private static String split(String code, long share) {
        return "{\"id\":1,\"name\":\"Dynamic Split\",\"type\":\"flat\",\"currency\":\"ZAR\",\"bearer_type\":\"account\","
                + "\"subaccounts\":[{\"subaccount\":{\"id\":9,\"subaccount_code\":\"" + code + "\",\"business_name\":\"S\"},"
                + "\"share\":" + share + "}],\"total_subaccounts\":1}";
    }

    private void postSigned(String body) throws Exception {
        mockMvc.perform(post("/api/v1/payments/paystack/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", PaystackSignatureTest.docHmac(body, KEY))
                        .content(body))
                .andExpect(status().isOk());
    }

    private VendorPayoutEntry entryOf(Long orderId, User vendor) {
        return entryRepository.findByOrderId(orderId).stream()
                .filter(e -> e.getVendor().getId().equals(vendor.getId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the subaccounted vendor's entry is PAID / SPLIT with the reference; the other stays PENDING / EFT")
    void splitMarksTheRightEntries(CapturedOutput output) throws Exception {
        Placed p = splitOrder("ok", true);
        String reference = "ERY-" + p.orderId() + "-split0000001";
        postSigned(body(p.orderId(), reference, 15_000, split("ACCT_oka", 8750)));

        assertThat(orderRepository.findById(p.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        VendorPayoutEntry a = entryOf(p.orderId(), p.a());
        assertThat(a.getStatus()).isEqualTo(PayoutEntryStatus.PAID);
        assertThat(a.getSettlementMode()).isEqualTo(SettlementMode.SPLIT);
        assertThat(a.getPaymentReference()).isEqualTo(reference);
        assertThat(a.getPaidAt()).isNotNull();
        assertThat(a.getNote()).isEqualTo("Settled by Paystack split");
        assertThat(a.getNetPayable()).isEqualByComparingTo("87.50");

        VendorPayoutEntry b = entryOf(p.orderId(), p.b());
        assertThat(b.getStatus()).isEqualTo(PayoutEntryStatus.PENDING);
        assertThat(b.getSettlementMode()).isEqualTo(SettlementMode.EFT);
        assertThat(b.getPaymentReference()).isNull();
        assertThat(output.getAll()).doesNotContain("SPLIT SHARE MISMATCH").doesNotContain("SPLIT MISSING");

        // The payout run never sees the split-settled entry; the EFT one is still its work.
        User admin = fixtures.admin(uniq("w-admin"));
        String token = "Bearer " + jwtService.generateToken(admin.getId(), admin.getRole().name());
        mockMvc.perform(get("/api/v1/admin/payouts/pending").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.vendorId == " + p.a().getId() + ")]").isEmpty())
                .andExpect(jsonPath("$[?(@.vendorId == " + p.b().getId() + ")].entries[0].orderId")
                        .value(org.hamcrest.Matchers.hasItem(p.orderId().intValue())));

        // The vendor's order view says how it was paid, with the reference and no timing words.
        String vendorToken = "Bearer " + jwtService.generateToken(p.a().getId(), "VENDOR");
        mockMvc.perform(get("/api/v1/vendor/orders/" + p.orderId()).header("Authorization", vendorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlement.mode").value("SPLIT"))
                .andExpect(jsonPath("$.settlement.reference").value(reference));
        String vendorBToken = "Bearer " + jwtService.generateToken(p.b().getId(), "VENDOR");
        mockMvc.perform(get("/api/v1/vendor/orders/" + p.orderId()).header("Authorization", vendorBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlement.mode").value("EFT"))
                .andExpect(jsonPath("$.settlement.reference").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("a split order whose charge.success carries no split logs SPLIT MISSING and marks nothing")
    void splitMissingIsLogged(CapturedOutput output) throws Exception {
        Placed p = splitOrder("missing", true);
        postSigned(body(p.orderId(), "ERY-" + p.orderId() + "-nosplit00001", 15_000, null));

        assertThat(orderRepository.findById(p.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(p.orderId());
        assertThat(entries).hasSize(2).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(PayoutEntryStatus.PENDING);
            assertThat(e.getSettlementMode()).isEqualTo(SettlementMode.EFT);
        });
        assertThat(output.getAll()).contains("SPLIT MISSING ON CHARGE.SUCCESS order " + p.orderId());
    }

    @Test
    @DisplayName("an order that was never split carries no split request, and a bare webhook is not an alert")
    void unsplitOrderIsNotAnAlert(CapturedOutput output) throws Exception {
        Placed p = splitOrder("plain", false);
        postSigned(body(p.orderId(), "ERY-" + p.orderId() + "-plain0000001", 15_000, null));
        assertThat(orderRepository.findById(p.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(output.getAll()).doesNotContain("SPLIT MISSING ON CHARGE.SUCCESS order " + p.orderId());
    }

    @Test
    @DisplayName("a reported share more than a cent off the ledger's net is still marked, and logged at ERROR")
    void mismatchIsMarkedAndLogged(CapturedOutput output) throws Exception {
        Placed p = splitOrder("mismatch", true);
        String reference = "ERY-" + p.orderId() + "-mismatch0001";
        postSigned(body(p.orderId(), reference, 15_000, split("ACCT_mismatcha", 8700)));

        VendorPayoutEntry a = entryOf(p.orderId(), p.a());
        assertThat(a.getStatus()).isEqualTo(PayoutEntryStatus.PAID);
        assertThat(a.getSettlementMode()).isEqualTo(SettlementMode.SPLIT);
        assertThat(output.getAll()).contains("SPLIT SHARE MISMATCH order " + p.orderId()
                + " vendor " + p.a().getId() + " expected 8750 got 8700");
    }

    @Test
    @DisplayName("a redelivered split webhook changes nothing the second time")
    void redeliveryIsIdempotent() throws Exception {
        Placed p = splitOrder("redeliver", true);
        String reference = "ERY-" + p.orderId() + "-redeliver001";
        String b = body(p.orderId(), reference, 15_000, split("ACCT_redelivera", 8750));
        postSigned(b);
        VendorPayoutEntry first = entryOf(p.orderId(), p.a());
        postSigned(b);
        VendorPayoutEntry again = entryOf(p.orderId(), p.a());
        assertThat(again.getPaidAt()).isEqualTo(first.getPaidAt());
        assertThat(entryRepository.findByOrderId(p.orderId())).hasSize(2);
    }
}
