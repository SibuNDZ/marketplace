package com.marketplace.api.payment;

import com.marketplace.api.discovery.PopularityJob;
import com.marketplace.api.discovery.ProductPopularity;
import com.marketplace.api.discovery.ProductPopularityRepository;
import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.payment.CheckoutPolicy.CheckoutNotOpenException;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.OrderItemRepository;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ReviewRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import com.marketplace.api.service.VendorOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The checkout guard: while payments run TEST keys, only admins can check out,
 * and their orders have no effect as sales anywhere.
 *
 * The test classpath runs Stripe with an sk_test_ key (test mode) and sets
 * test-mode-checkout to open; this class turns the guard on the way
 * production has it. That live keys lift the guard is asserted in
 * CheckoutGuardLiveModeTest, since the mode is fixed at boot.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutGuardTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.payments.test-mode-checkout", () -> "admins-only");
    }

    @Autowired OrderService                orderService;
    @Autowired VendorOrderService          vendorOrderService;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired OrderRepository             orderRepository;
    @Autowired OrderItemRepository         orderItemRepository;
    @Autowired CartRepository              cartRepository;
    @Autowired ProductRepository           productRepository;
    @Autowired ReviewRepository            reviewRepository;
    @Autowired VendorPayoutEntryRepository entryRepository;
    @Autowired ProductPopularityRepository popularityRepository;
    @Autowired PopularityJob               popularityJob;
    @Autowired TestFixtures                fixtures;
    @Autowired MockMvc                     mockMvc;
    @Autowired JwtService                  jwtService;
    @Autowired PlatformTransactionManager  txManager;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    private Product productOf(User vendor) {
        return fixtures.productForVendor(uniq("Gift Set"), uniq("SKU-CG"),
                new BigDecimal("190.00"), 5, vendor);
    }

    /** An admin's cart holding one unit of the product. */
    private User adminWithCart(Product p) {
        User admin = fixtures.admin(uniq("guard-admin"));
        fixtures.addToCart(admin.getId(), p, 1);
        return admin;
    }

    // ── shoppers are turned away, losing nothing ────────────────────────────

    @Test
    @DisplayName("a customer cannot place an order, and keeps their cart and the stock")
    void customerTurnedAway() {
        Product p = productOf(fixtures.vendor(uniq("guard-vendor")));
        User buyer = fixtures.customerWithCartOf(uniq("guard-buyer"), p);

        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(CheckoutNotOpenException.class);

        // Read inside a transaction: the cart's items are a lazy collection.
        Integer cartLines = new TransactionTemplate(txManager).execute(tx ->
                cartRepository.findByUserId(buyer.getId()).orElseThrow().getItems().size());
        assertThat(cartLines).isEqualTo(1);
        assertThat(productRepository.findById(p.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("a customer cannot start a payment either, even for an order placed earlier")
    void payTurnedAway() throws Exception {
        User buyer = fixtures.customer(uniq("pay-buyer"));
        // Refused before the order is even looked up, so any id shows it.
        mockMvc.perform(post("/api/v1/orders/999999/pay")
                        .header("Authorization", "Bearer " + tokenFor(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"Test Buyer","phone":"0821234567",
                                 "addressLine1":"1 Test St","city":"East London",
                                 "province":"Eastern Cape","postalCode":"5201"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Checkout not open yet"));
    }

    @Test
    @DisplayName("the storefront can see checkout is open to admins only")
    void healthSaysAdminsOnly() throws Exception {
        mockMvc.perform(get("/api/v1/payments/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("test"))
                .andExpect(jsonPath("$.checkoutOpenTo").value("admins"));
    }

    // ── an admin can test, and it counts as nothing ─────────────────────────

    @Test
    @DisplayName("an admin's order is flagged as a test order")
    void adminOrderFlagged() {
        User admin = adminWithCart(productOf(fixtures.vendor(uniq("flag-vendor"))));
        OrderResponse order = orderService.placeOrder(admin.getId());

        assertThat(order.testOrder()).isTrue();
        assertThat(orderRepository.findById(order.id()).orElseThrow().isTestOrder()).isTrue();
    }

    @Test
    @DisplayName("a paid test order writes no payout and is hidden from the vendor (emails: TestOrderEmailTest)")
    void paidTestOrderIsNotASale() {
        User vendor = fixtures.vendor(uniq("quiet-vendor"));
        Product p = productOf(vendor);
        User admin = adminWithCart(p);
        OrderResponse order = orderService.placeOrder(admin.getId());

        paymentEventService.handleCheckoutCompleted(order.id(), "Yoco");

        // No payout owed: nothing on the list the admin approves from.
        assertThat(entryRepository.findByOrderId(order.id())).isEmpty();

        // Not in the vendor's dashboard, not openable, not shippable.
        assertThat(vendorOrderService.list(vendor.getId(), PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThatThrownBy(() -> vendorOrderService.get(vendor.getId(), order.id()))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> vendorOrderService.markShipped(vendor.getId(), order.id(), null))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("a test order never counts as a sale, a recent buyer, or a verified purchase")
    void testOrderAddsNoSignals() {
        Product p = productOf(fixtures.vendor(uniq("signal-vendor")));
        User admin = adminWithCart(p);
        OrderResponse order = orderService.placeOrder(admin.getId());
        fixtures.deliverOrder(order.id(), admin.getId()); // PAID -> SHIPPED -> DELIVERED

        popularityJob.rebuild();
        assertThat(popularityRepository.findById(p.getId())
                .map(ProductPopularity::getSalesCount).orElse(0L)).isZero();
        assertThat(orderItemRepository.countDistinctRecentBuyers(p.getId())).isZero();
        // Delivered, but it was a test: no review may rest on it.
        assertThat(reviewRepository.hasDeliveredPurchase(admin.getId(), p.getId())).isFalse();
    }

    @Test
    @DisplayName("the payout backfill never 'repairs' a test order by writing its payouts")
    void backfillSkipsTestOrders() {
        User admin = adminWithCart(productOf(fixtures.vendor(uniq("backfill-vendor"))));
        OrderResponse order = orderService.placeOrder(admin.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Yoco");

        List<Long> workList = orderRepository.findIdsMissingPayoutEntries(List.of(
                com.marketplace.api.entity.OrderStatus.PAID,
                com.marketplace.api.entity.OrderStatus.SHIPPED,
                com.marketplace.api.entity.OrderStatus.DELIVERED));
        assertThat(workList).doesNotContain(order.id());
    }
}
