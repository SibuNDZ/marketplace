package com.marketplace.api.payout;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.dto.PayoutDtos.BatchSummary;
import com.marketplace.api.dto.PayoutDtos.VendorPendingGroup;
import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.payout.PayoutExceptions.InvalidPayoutStateException;
import com.marketplace.api.payout.PayoutExceptions.VendorNotPayableException;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payout run end to end: pending -> approve -> export -> mark paid,
 * plus the rails (state order enforced, masking everywhere, admin-only).
 *
 * Entries come through the REAL path (place order, deliver webhook), not by
 * inserting ledger rows directly — the run must work on what the ledger
 * actually writes.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PayoutOperationsTest {

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

    @Autowired PayoutAdminService          payoutAdminService;
    @Autowired OrderService                orderService;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired VendorPayoutEntryRepository entryRepository;
    @Autowired UserRepository              userRepository;
    @Autowired TestFixtures                fixtures;
    @Autowired MockMvc                     mockMvc;
    @Autowired JwtService                  jwtService;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A vendor whose banking details are complete enough to export. */
    private User bankedVendor(String tag) {
        User vendor = fixtures.vendor(uniq(tag));
        vendor.setAccountHolderName("Pty Ltd " + tag);
        vendor.setBankName("Nedbank");
        vendor.setAccountNumber("1122336789");
        vendor.setBranchCode("198765");
        vendor.setAccountType(BankAccountType.CHEQUE);
        return userRepository.save(vendor);
    }

    /** Places and pays one 100.00 order against the vendor; returns its entry. */
    private VendorPayoutEntry paidEntry(User vendor) {
        Product p = fixtures.productForVendor(uniq("Payout Item"), uniq("SKU-PO"),
                new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("payout-buyer"), p);
        OrderResponse order = orderService.placeOrder(buyer.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Yoco");
        return entryRepository.findByOrderId(order.id()).get(0);
    }

    private VendorPendingGroup groupOf(Long vendorId) {
        return payoutAdminService.pending().stream()
                .filter(g -> g.vendorId().equals(vendorId))
                .findFirst().orElseThrow();
    }

    // ── the happy path ───────────────────────────────────────────────────

    @Test
    @DisplayName("approve -> export -> mark paid, with every timestamp and status moving")
    void fullRun() {
        User vendor = bankedVendor("run-vendor");
        VendorPayoutEntry e1 = paidEntry(vendor);
        VendorPayoutEntry e2 = paidEntry(vendor);
        User admin = fixtures.admin(uniq("run-admin"));

        // Pending groups the two entries under one vendor with the right sum.
        VendorPendingGroup group = groupOf(vendor.getId());
        assertThat(group.entries()).hasSize(2);
        assertThat(group.totalNet()).isEqualByComparingTo("175.00"); // 2 x 87.50

        // Approve.
        BatchSummary batch = payoutAdminService.approve(
                List.of(e1.getId(), e2.getId()), admin.getId());
        assertThat(batch.state()).isEqualTo("APPROVED");
        assertThat(batch.totalNet()).isEqualByComparingTo("175.00");
        assertThat(entryRepository.findById(e1.getId()).orElseThrow().getStatus())
                .isEqualTo(PayoutEntryStatus.APPROVED);
        // And they leave the pending view.
        assertThat(payoutAdminService.pending().stream()
                .noneMatch(g -> g.vendorId().equals(vendor.getId()))).isTrue();

        // Export: one line per vendor, summed, full account number FOR THE
        // BANK (this is the one unmasked surface), reference = code + period.
        String csv = payoutAdminService.exportCsv(batch.id());
        assertThat(csv).startsWith("Name,AccountNumber,BranchCode,Amount,Reference");
        assertThat(csv).contains("1122336789").contains("198765").contains("175.00")
                .contains("ERESTYU-V" + vendor.getId() + "-");
        assertThat(csv.trim().split("\r\n")).hasSize(2); // header + ONE summed line

        // Mark paid.
        BatchSummary paid = payoutAdminService.markPaid(batch.id(), "NEDEFT-42", admin.getId());
        assertThat(paid.state()).isEqualTo("PAID");
        VendorPayoutEntry after = entryRepository.findById(e1.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PayoutEntryStatus.PAID);
        assertThat(after.getPaymentReference()).isEqualTo("NEDEFT-42");
        assertThat(after.getPaidAt()).isNotNull();
    }

    // ── the rails ────────────────────────────────────────────────────────

    @Test
    @DisplayName("banking is masked to last 4 in the pending view")
    void maskingInPendingView() {
        User vendor = bankedVendor("mask-vendor");
        paidEntry(vendor);

        VendorPendingGroup group = groupOf(vendor.getId());
        assertThat(group.banking().accountNumberLast4()).isEqualTo("···6789");
        assertThat(group.banking().complete()).isTrue();
        // The full number appears NOWHERE in the DTO tree.
        assertThat(group.toString()).doesNotContain("1122336789");
    }

    @Test
    @DisplayName("mark-paid before export is refused: you cannot have paid a file that never existed")
    void paidBeforeExportRefused() {
        User vendor = bankedVendor("order-vendor");
        VendorPayoutEntry e = paidEntry(vendor);
        User admin = fixtures.admin(uniq("order-admin"));

        BatchSummary batch = payoutAdminService.approve(List.of(e.getId()), admin.getId());
        assertThatThrownBy(() -> payoutAdminService.markPaid(batch.id(), "REF", admin.getId()))
                .isInstanceOf(InvalidPayoutStateException.class)
                .hasMessageContaining("not been exported");
    }

    @Test
    @DisplayName("a paid batch cannot be re-exported or re-paid")
    void paidBatchIsTerminal() {
        User vendor = bankedVendor("term-vendor");
        VendorPayoutEntry e = paidEntry(vendor);
        User admin = fixtures.admin(uniq("term-admin"));

        BatchSummary batch = payoutAdminService.approve(List.of(e.getId()), admin.getId());
        payoutAdminService.exportCsv(batch.id());
        payoutAdminService.markPaid(batch.id(), "REF-1", admin.getId());

        assertThatThrownBy(() -> payoutAdminService.markPaid(batch.id(), "REF-2", admin.getId()))
                .isInstanceOf(InvalidPayoutStateException.class);
        // Re-export after payment risks a double upload; export before
        // payment is allowed (files get lost), tested implicitly above.
        assertThatThrownBy(() -> payoutAdminService.exportCsv(batch.id()))
                .isInstanceOf(InvalidPayoutStateException.class);
    }

    @Test
    @DisplayName("already-batched entries cannot be approved again")
    void doubleApproveRefused() {
        User vendor = bankedVendor("double-vendor");
        VendorPayoutEntry e = paidEntry(vendor);
        User admin = fixtures.admin(uniq("double-admin"));

        payoutAdminService.approve(List.of(e.getId()), admin.getId());
        assertThatThrownBy(() -> payoutAdminService.approve(List.of(e.getId()), admin.getId()))
                .isInstanceOf(InvalidPayoutStateException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    @DisplayName("a vendor whose selected sum is non-positive carries forward, not into a batch")
    void negativeSumCarriesForward() {
        User vendor = bankedVendor("neg-vendor");
        VendorPayoutEntry primary = paidEntry(vendor);
        // A clawback bigger than the pending entry: net across the pair is
        // negative — a bank cannot transfer that.
        VendorPayoutEntry clawback = new VendorPayoutEntry();
        clawback.setOrder(primary.getOrder());
        clawback.setVendor(primary.getVendor());
        clawback.setKind(com.marketplace.api.entity.PayoutEntryKind.ADJUSTMENT);
        clawback.setStatus(PayoutEntryStatus.ADJUSTED);
        clawback.setItemSubtotal(new BigDecimal("-200.00"));
        clawback.setDeliveryFee(BigDecimal.ZERO);
        clawback.setCommissionRate(primary.getCommissionRate());
        clawback.setCommissionAmount(new BigDecimal("-25.00"));
        clawback.setNetPayable(new BigDecimal("-175.00"));
        clawback.setNote("test clawback");
        entryRepository.save(clawback);
        User admin = fixtures.admin(uniq("neg-admin"));

        assertThatThrownBy(() -> payoutAdminService.approve(
                List.of(primary.getId(), clawback.getId()), admin.getId()))
                .isInstanceOf(VendorNotPayableException.class)
                .hasMessageContaining("carry forward");
    }

    @Test
    @DisplayName("export is refused while any vendor's banking is incomplete")
    void incompleteBankingBlocksExport() {
        User vendor = fixtures.vendor(uniq("unbanked-vendor")); // NO banking set
        VendorPayoutEntry e = paidEntry(vendor);
        User admin = fixtures.admin(uniq("unbanked-admin"));

        // The masked view says so up front...
        assertThat(groupOf(vendor.getId()).banking().complete()).isFalse();

        // ...approval is still fine (the debt is real)...
        BatchSummary batch = payoutAdminService.approve(List.of(e.getId()), admin.getId());

        // ...but the bank file refuses to contain an empty account number.
        assertThatThrownBy(() -> payoutAdminService.exportCsv(batch.id()))
                .isInstanceOf(VendorNotPayableException.class)
                .hasMessageContaining("incomplete banking");
    }

    // ── the guard ────────────────────────────────────────────────────────

    @Test
    @DisplayName("non-admins get 403 from every payout endpoint")
    void nonAdminForbidden() throws Exception {
        User vendor = fixtures.vendor(uniq("guard-vendor"));
        String vendorToken = jwtService.generateToken(vendor.getId(), vendor.getRole().name());

        mockMvc.perform(get("/api/v1/admin/payouts/pending")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/payouts/batches")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isForbidden());
        // And unauthenticated is not better.
        mockMvc.perform(get("/api/v1/admin/payouts/pending"))
                .andExpect(status().isUnauthorized());
    }
}
