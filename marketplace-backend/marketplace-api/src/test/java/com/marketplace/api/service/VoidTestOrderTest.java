package com.marketplace.api.service;

import com.marketplace.api.discovery.ProductPopularity;
import com.marketplace.api.discovery.ProductPopularityRepository;
import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.dto.PayoutDtos.BatchSummary;
import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.payout.PayoutAdminService;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.security.JwtService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voiding a test-mode order: a payment that moved no money must stop
 * behaving like a sale. The test classpath runs Stripe with an sk_test_ key,
 * so payments are in test mode here; the live-mode refusal has its own class
 * (VoidTestOrderLiveModeTest), because the mode is fixed at boot.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class VoidTestOrderTest {

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

    @Autowired OrderService                  orderService;
    @Autowired PaymentEventService           paymentEventService;
    @Autowired PayoutAdminService            payoutAdminService;
    @Autowired OrderRepository               orderRepository;
    @Autowired OrderStatusHistoryRepository  historyRepository;
    @Autowired ProductRepository             productRepository;
    @Autowired VendorPayoutEntryRepository   entryRepository;
    @Autowired ProductPopularityRepository   popularityRepository;
    @Autowired UserRepository                userRepository;
    @Autowired TestFixtures                  fixtures;
    @Autowired MockMvc                       mockMvc;
    @Autowired JwtService                    jwtService;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    private record Paid(Long orderId, Product product, User vendor) {}

    /** One unit of a 5-stock, R190 product, placed and paid (test mode). */
    private Paid paidOrder(User vendor) {
        Product p = fixtures.productForVendor(uniq("Gift Set"), uniq("SKU-VD"),
                new BigDecimal("190.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("void-buyer"), p);
        OrderResponse order = orderService.placeOrder(buyer.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Yoco");
        return new Paid(order.id(), p, vendor);
    }

    private int stockOf(Product p) {
        return productRepository.findById(p.getId()).orElseThrow().getStockQuantity();
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private List<VendorPayoutEntry> entriesOf(Long orderId) {
        return entryRepository.findByOrderId(orderId);
    }

    // ── the happy path ─────────────────────────────────────────────────────

    @Test
    @DisplayName("voiding a paid test order cancels it, restocks, voids payouts and records why")
    void voidPaidOrder() {
        Paid paid = paidOrder(fixtures.vendor(uniq("void-vendor")));
        User admin = fixtures.admin(uniq("void-admin"));
        assertThat(stockOf(paid.product())).isEqualTo(4);
        assertThat(entriesOf(paid.orderId())).extracting(VendorPayoutEntry::getStatus)
                .containsOnly(PayoutEntryStatus.PENDING);

        OrderService.VoidResult result =
                orderService.voidTestOrder(paid.orderId(), admin.getId(), "Owner's checkout test");

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.warnings()).isEmpty();
        assertThat(statusOf(paid.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(paid.product())).isEqualTo(5);
        // Voided, not deleted: the ledger stays append-only and auditable.
        assertThat(entriesOf(paid.orderId())).extracting(VendorPayoutEntry::getStatus)
                .containsOnly(PayoutEntryStatus.VOID);
        // And it has left the payout work list the admin approves from.
        assertThat(payoutAdminService.pending().stream()
                .noneMatch(g -> g.vendorId().equals(paid.vendor().getId()))).isTrue();
        // The history is the only record of why a paid order became cancelled.
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(paid.orderId()))
                .last()
                .satisfies(h -> {
                    assertThat(h.getFromStatus()).isEqualTo(OrderStatus.PAID);
                    assertThat(h.getToStatus()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(h.getChangedBy().getId()).isEqualTo(admin.getId());
                    assertThat(h.getNote()).isEqualTo("Voided as test order: Owner's checkout test");
                });
    }

    @Test
    @DisplayName("a delivered test order can be voided too, not just a freshly paid one")
    void voidDeliveredOrder() {
        User vendor = fixtures.vendor(uniq("deliv-vendor"));
        Product p = fixtures.productForVendor(uniq("Honey"), uniq("SKU-VH"),
                new BigDecimal("89.00"), 3, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("deliv-buyer"), p);
        User admin = fixtures.admin(uniq("deliv-admin"));
        OrderResponse order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());
        assertThat(statusOf(order.id())).isEqualTo(OrderStatus.DELIVERED);

        orderService.voidTestOrder(order.id(), admin.getId(), "Marked delivered to test reviews");

        assertThat(statusOf(order.id())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(p)).isEqualTo(3);
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    @DisplayName("refused, with nothing changed, if the vendor has already been paid out")
    void refusedAfterPayout() {
        User vendor = fixtures.vendor(uniq("paidout-vendor"));
        vendor.setAccountHolderName("Paid Out Pty");
        vendor.setBankName("Nedbank");
        vendor.setAccountNumber("1122336789");
        vendor.setBranchCode("198765");
        vendor.setAccountType(BankAccountType.CHEQUE);
        userRepository.save(vendor);
        Paid paid = paidOrder(vendor);
        User admin = fixtures.admin(uniq("paidout-admin"));

        BatchSummary batch = payoutAdminService.approve(
                entriesOf(paid.orderId()).stream().map(VendorPayoutEntry::getId).toList(), admin.getId());
        payoutAdminService.exportCsv(batch.id());
        payoutAdminService.markPaid(batch.id(), "NEDEFT-7", admin.getId());

        // Real money reached the vendor, so this is not test data.
        assertThatThrownBy(() -> orderService.voidTestOrder(paid.orderId(), admin.getId(), "test"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("already been paid out");

        // Rolled back whole: status, stock and ledger exactly as before.
        assertThat(statusOf(paid.orderId())).isEqualTo(OrderStatus.PAID);
        assertThat(stockOf(paid.product())).isEqualTo(4);
        assertThat(entriesOf(paid.orderId())).extracting(VendorPayoutEntry::getStatus)
                .containsOnly(PayoutEntryStatus.PAID);
    }

    @Test
    @DisplayName("an entry approved into a batch is voided, and the admin is told to check the bank file")
    void approvedEntryWarns() {
        Paid paid = paidOrder(fixtures.vendor(uniq("approved-vendor")));
        User admin = fixtures.admin(uniq("approved-admin"));
        BatchSummary batch = payoutAdminService.approve(
                entriesOf(paid.orderId()).stream().map(VendorPayoutEntry::getId).toList(), admin.getId());

        OrderService.VoidResult result = orderService.voidTestOrder(paid.orderId(), admin.getId(), "test");

        assertThat(entriesOf(paid.orderId())).extracting(VendorPayoutEntry::getStatus)
                .containsOnly(PayoutEntryStatus.VOID);
        assertThat(result.warnings()).singleElement().asString()
                .contains("batch " + batch.id())
                .contains("bank file");
    }

    @Test
    @DisplayName("a PENDING order is refused and pointed at the ordinary cancel")
    void pendingRefused() {
        User vendor = fixtures.vendor(uniq("pend-vendor"));
        Product p = fixtures.productForVendor(uniq("Pending Item"), uniq("SKU-VP"),
                new BigDecimal("50.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("pend-buyer"), p);
        OrderResponse order = orderService.placeOrder(buyer.getId());
        User admin = fixtures.admin(uniq("pend-admin"));

        assertThatThrownBy(() -> orderService.voidTestOrder(order.id(), admin.getId(), "test"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Cancel a PENDING order instead");
        assertThat(statusOf(order.id())).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("an order cannot be voided twice")
    void notTwice() {
        Paid paid = paidOrder(fixtures.vendor(uniq("twice-vendor")));
        User admin = fixtures.admin(uniq("twice-admin"));
        orderService.voidTestOrder(paid.orderId(), admin.getId(), "test");

        assertThatThrownBy(() -> orderService.voidTestOrder(paid.orderId(), admin.getId(), "again"))
                .isInstanceOf(InvalidOrderStateException.class);
        // Restocked once, not twice.
        assertThat(stockOf(paid.product())).isEqualTo(5);
    }

    // ── over HTTP ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the endpoint voids, and the product's public sold count drops at once")
    void endpointVoidsAndCorrectsSoldCount() throws Exception {
        Paid paid = paidOrder(fixtures.vendor(uniq("http-vendor")));
        User admin = fixtures.admin(uniq("http-admin"));

        mockMvc.perform(post("/api/v1/admin/orders/" + paid.orderId() + "/void")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Checkout test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.warnings").isEmpty());

        // Rebuilt by the endpoint, not left for the hourly job.
        assertThat(popularityRepository.findById(paid.product().getId())
                .map(ProductPopularity::getSalesCount).orElse(0L)).isZero();
    }

    @Test
    @DisplayName("only admins can void, and a reason is required")
    void endpointRules() throws Exception {
        Paid paid = paidOrder(fixtures.vendor(uniq("rules-vendor")));

        mockMvc.perform(post("/api/v1/admin/orders/" + paid.orderId() + "/void")
                        .header("Authorization", "Bearer " + tokenFor(paid.vendor()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not mine to void\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/orders/" + paid.orderId() + "/void")
                        .header("Authorization", "Bearer " + tokenFor(fixtures.admin(uniq("noreason"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(paid.orderId())).isEqualTo(OrderStatus.PAID);
    }
}
