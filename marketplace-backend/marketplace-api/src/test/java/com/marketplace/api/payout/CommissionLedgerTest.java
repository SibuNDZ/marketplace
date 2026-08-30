package com.marketplace.api.payout;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.PayoutEntryKind;
import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.service.OrderAdminService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commission ledger, end to end through the REAL payment path: place an
 * order, deliver the webhook, read the debt.
 *
 * The arithmetic assertions are deliberately literal numbers, not re-runs of
 * the production formula — a test that recomputes commission with the same
 * code it is testing would pass through any drift. 12.5% of 100.00 is 12.50
 * as a fact, written down.
 *
 * The rate is PINNED to 0.125 below (it was application.yml's placeholder
 * when these numbers were hand-computed; the production default is now the
 * decided 10%). Pinning keeps every literal assertion valid regardless of
 * future rate decisions. Tests that need a different rate use the
 * per-vendor override, which is also how the override path earns its
 * coverage.
 */
@Testcontainers
@SpringBootTest
class CommissionLedgerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Every arithmetic assertion in this class was hand-computed at
        // 12.5%; pin it so rate decisions never silently invalidate them.
        registry.add("app.payouts.commission-rate", () -> "0.125");
    }

    @Autowired OrderService                orderService;
    @Autowired OrderAdminService           orderAdminService;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired CommissionLedgerService     ledger;
    @Autowired VendorPayoutEntryRepository entryRepository;
    @Autowired UserRepository              userRepository;
    @Autowired TestFixtures                fixtures;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Places and pays an order of the given products for a fresh customer. */
    private Long paidOrder(Product... products) {
        User buyer = fixtures.customerWithCartOf(uniq("ledger-buyer"), products);
        OrderResponse order = orderService.placeOrder(buyer.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Stripe");
        return order.id();
    }

    private VendorPayoutEntry only(Long orderId) {
        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(orderId);
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    // ── writing the debt ─────────────────────────────────────────────────

    @Test
    @DisplayName("a paid order writes one PENDING entry per vendor with the correct split")
    void multiVendorSplit() {
        // Vendor A: R200 item + R50 delivery. Vendor B: R100 item, free delivery.
        User vendorA = fixtures.vendor(uniq("split-a"));
        vendorA.setDeliveryFee(new BigDecimal("50.00"));
        userRepository.save(vendorA);
        User vendorB = fixtures.vendor(uniq("split-b"));

        Product pa = fixtures.productForVendor(uniq("A Item"), uniq("SKU-LA"), new BigDecimal("200.00"), 5, vendorA);
        Product pb = fixtures.productForVendor(uniq("B Item"), uniq("SKU-LB"), new BigDecimal("100.00"), 5, vendorB);

        Long orderId = paidOrder(pa, pb);

        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(orderId);
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(PayoutEntryStatus.PENDING);
            assertThat(e.getKind()).isEqualTo(PayoutEntryKind.PRIMARY);
            assertThat(e.getCommissionRate()).isEqualByComparingTo("0.125");
        });

        VendorPayoutEntry a = entries.stream()
                .filter(e -> e.getVendor().getId().equals(vendorA.getId())).findFirst().orElseThrow();
        // 200 * 0.125 = 25.00; net = 200 - 25 + 50 = 225.00
        assertThat(a.getItemSubtotal()).isEqualByComparingTo("200.00");
        assertThat(a.getDeliveryFee()).isEqualByComparingTo("50.00");
        assertThat(a.getCommissionAmount()).isEqualByComparingTo("25.00");
        assertThat(a.getNetPayable()).isEqualByComparingTo("225.00");

        VendorPayoutEntry b = entries.stream()
                .filter(e -> e.getVendor().getId().equals(vendorB.getId())).findFirst().orElseThrow();
        // 100 * 0.125 = 12.50; net = 100 - 12.50 + 0 = 87.50
        assertThat(b.getItemSubtotal()).isEqualByComparingTo("100.00");
        assertThat(b.getDeliveryFee()).isEqualByComparingTo("0.00");
        assertThat(b.getCommissionAmount()).isEqualByComparingTo("12.50");
        assertThat(b.getNetPayable()).isEqualByComparingTo("87.50");
    }

    @Test
    @DisplayName("the delivery fee passes through in full, never commissioned")
    void deliveryFeeNotCommissioned() {
        User vendor = fixtures.vendor(uniq("fee-vendor"));
        vendor.setDeliveryFee(new BigDecimal("80.00"));
        userRepository.save(vendor);
        Product p = fixtures.productForVendor(uniq("Fee Item"), uniq("SKU-LF"), new BigDecimal("100.00"), 5, vendor);

        VendorPayoutEntry e = only(paidOrder(p));

        // Commission on 100.00 alone is 12.50. Commission on 180.00 (items +
        // fee, the bug this asserts against) would be 22.50.
        assertThat(e.getCommissionAmount()).isEqualByComparingTo("12.50");
        assertThat(e.getNetPayable()).isEqualByComparingTo("167.50"); // 100 - 12.50 + 80
    }

    @Test
    @DisplayName("sub-cent rounding goes DOWN: the platform absorbs it, never the vendor")
    void roundingFavoursVendor() {
        User vendor = fixtures.vendor(uniq("round-vendor"));
        Product p = fixtures.productForVendor(uniq("Odd Price"), uniq("SKU-LR"), new BigDecimal("99.99"), 5, vendor);

        VendorPayoutEntry e = only(paidOrder(p));

        // 99.99 * 0.125 = 12.49875 exactly. HALF_UP would take 12.50 from the
        // vendor; DOWN takes 12.49 and the platform eats the fraction.
        assertThat(e.getCommissionAmount()).isEqualByComparingTo("12.49");
        assertThat(e.getNetPayable()).isEqualByComparingTo("87.50");
    }

    @Test
    @DisplayName("duplicate webhook delivery writes exactly one entry set")
    void duplicateWebhookIdempotent() {
        User vendor = fixtures.vendor(uniq("dup-vendor"));
        Product p = fixtures.productForVendor(uniq("Dup Item"), uniq("SKU-LD"), new BigDecimal("100.00"), 5, vendor);

        Long orderId = paidOrder(p);
        // Stripe/Yoco retry deliveries as a matter of course; this is the
        // normal path, not an error path.
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");
        paymentEventService.handleCheckoutCompleted(orderId, "Yoco");

        assertThat(entryRepository.findByOrderId(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("an order that never reaches PAID produces no entries at all")
    void unpaidOrderNoEntries() {
        User vendor = fixtures.vendor(uniq("unpaid-vendor"));
        Product p = fixtures.productForVendor(uniq("Unpaid Item"), uniq("SKU-LU"), new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("unpaid-buyer"), p);

        OrderResponse order = orderService.placeOrder(buyer.getId());
        // The expiry job's path: PENDING -> CANCELLED without money moving.
        orderService.cancelExpired(order.id());

        assertThat(entryRepository.findByOrderId(order.id())).isEmpty();
    }

    // ── rate resolution ──────────────────────────────────────────────────

    @Test
    @DisplayName("a per-vendor rate override wins, and the snapshot never moves afterwards")
    void overrideSnapshots() {
        User vendor = fixtures.vendor(uniq("override-vendor"));
        vendor.setCommissionRate(new BigDecimal("0.2000"));
        userRepository.save(vendor);
        Product p = fixtures.productForVendor(uniq("Override Item"), uniq("SKU-LO"), new BigDecimal("100.00"), 5, vendor);

        VendorPayoutEntry e = only(paidOrder(p));
        assertThat(e.getCommissionRate()).isEqualByComparingTo("0.2000");
        assertThat(e.getCommissionAmount()).isEqualByComparingTo("20.00");
        assertThat(e.getNetPayable()).isEqualByComparingTo("80.00");

        // The vendor renegotiates. History must not move — same principle as
        // OrderItem.priceAtPurchase.
        vendor.setCommissionRate(new BigDecimal("0.0500"));
        userRepository.save(vendor);

        VendorPayoutEntry after = entryRepository.findById(e.getId()).orElseThrow();
        assertThat(after.getCommissionRate()).isEqualByComparingTo("0.2000");
        assertThat(after.getNetPayable()).isEqualByComparingTo("80.00");
    }

    // ── reversals ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a full refund before payout voids the entries; nothing is deleted")
    void fullRefundVoids() {
        User vendor = fixtures.vendor(uniq("refund-vendor"));
        Product p = fixtures.productForVendor(uniq("Refund Item"), uniq("SKU-LV"), new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("refund-buyer"), p);
        User admin = fixtures.admin(uniq("refund-admin"));

        OrderResponse order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId()); // PAID -> SHIPPED -> DELIVERED
        orderAdminService.transition(order.id(), OrderStatus.REFUNDED, admin.getId(), "Customer return");

        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(order.id());
        assertThat(entries).hasSize(1); // the row SURVIVES — append-only history
        assertThat(entries.get(0).getStatus()).isEqualTo(PayoutEntryStatus.VOID);
        assertThat(entries.get(0).getNote()).contains("refunded");
    }

    @Test
    @DisplayName("a full refund after payout claws back what was actually paid")
    void refundAfterPayoutClawsBack() {
        User vendor = fixtures.vendor(uniq("claw-vendor"));
        Product p = fixtures.productForVendor(uniq("Claw Item"), uniq("SKU-LC"), new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("claw-buyer"), p);
        User admin = fixtures.admin(uniq("claw-admin"));

        OrderResponse order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());

        // Simulate the payout run having happened: the entry was paid.
        VendorPayoutEntry primary = only(order.id());
        primary.setStatus(PayoutEntryStatus.PAID);
        entryRepository.save(primary);

        orderAdminService.transition(order.id(), OrderStatus.REFUNDED, admin.getId(), "Return after payout");

        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(order.id());
        assertThat(entries).hasSize(2);

        // The paid row is untouched — money DID leave, and the ledger says so.
        VendorPayoutEntry paid = entries.stream()
                .filter(e -> e.getKind() == PayoutEntryKind.PRIMARY).findFirst().orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(PayoutEntryStatus.PAID);

        // The clawback negates exactly what was paid: -(100 - 12.50 + 0).
        VendorPayoutEntry clawback = entries.stream()
                .filter(e -> e.getKind() == PayoutEntryKind.ADJUSTMENT).findFirst().orElseThrow();
        assertThat(clawback.getStatus()).isEqualTo(PayoutEntryStatus.ADJUSTED);
        assertThat(clawback.getNetPayable()).isEqualByComparingTo("-87.50");
        assertThat(clawback.getNote()).contains("Clawback");
    }

    @Test
    @DisplayName("a partial refund writes a negative adjustment at the ORIGINAL rate")
    void partialRefundAdjusts() {
        User vendor = fixtures.vendor(uniq("adjust-vendor"));
        vendor.setCommissionRate(new BigDecimal("0.2000"));
        userRepository.save(vendor);
        Product p = fixtures.productForVendor(uniq("Adjust Item"), uniq("SKU-LP"), new BigDecimal("100.00"), 5, vendor);

        Long orderId = paidOrder(p);

        // Rate changes BETWEEN payment and partial refund. The adjustment must
        // use the entry's snapshotted 0.20, not today's 0.05 — otherwise a
        // refund becomes a rate-change backdoor into old orders.
        vendor.setCommissionRate(new BigDecimal("0.0500"));
        userRepository.save(vendor);

        ledger.recordAdjustment(orderId, vendor.getId(),
                new BigDecimal("40.00"), BigDecimal.ZERO, "Partial return: one of two units");

        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(orderId);
        assertThat(entries).hasSize(2);

        VendorPayoutEntry adjustment = entries.stream()
                .filter(e -> e.getKind() == PayoutEntryKind.ADJUSTMENT).findFirst().orElseThrow();
        assertThat(adjustment.getItemSubtotal()).isEqualByComparingTo("-40.00");
        assertThat(adjustment.getCommissionRate()).isEqualByComparingTo("0.2000");
        assertThat(adjustment.getCommissionAmount()).isEqualByComparingTo("-8.00");
        assertThat(adjustment.getNetPayable()).isEqualByComparingTo("-32.00"); // -(40 - 8)

        // The primary is untouched: corrections are new rows, not edits.
        VendorPayoutEntry primary = entries.stream()
                .filter(e -> e.getKind() == PayoutEntryKind.PRIMARY).findFirst().orElseThrow();
        assertThat(primary.getNetPayable()).isEqualByComparingTo("80.00");
        assertThat(primary.getStatus()).isEqualTo(PayoutEntryStatus.PENDING);
    }

    // ── backfill ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("backfill preview computes without writing; commit writes the same numbers")
    void backfillPreviewThenCommit() {
        User vendor = fixtures.vendor(uniq("backfill-vendor"));
        Product p = fixtures.productForVendor(uniq("Backfill Item"), uniq("SKU-LK"), new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("backfill-buyer"), p);
        OrderResponse order = orderService.placeOrder(buyer.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Stripe");

        // Simulate a pre-ledger order by erasing its entries (history: orders
        // 1-13 in production predate V27).
        entryRepository.deleteAll(entryRepository.findByOrderId(order.id()));

        List<String> preview = ledger.previewById(order.id());
        assertThat(preview).hasSize(1);
        assertThat(preview.get(0)).contains("net 87.50");
        assertThat(entryRepository.findByOrderId(order.id())).isEmpty(); // preview wrote NOTHING

        ledger.recordOnPaidById(order.id());
        VendorPayoutEntry e = only(order.id());
        assertThat(e.getNetPayable()).isEqualByComparingTo("87.50");

        // And a second run is a no-op, same as the webhook path.
        ledger.recordOnPaidById(order.id());
        assertThat(entryRepository.findByOrderId(order.id())).hasSize(1);
    }
}
