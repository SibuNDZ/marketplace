package com.marketplace.api.payment;

import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.payment.PaymentEventService.ProviderPayment;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An order placed while checkout was guarded (test keys) is a test order for
 * life: no payout, no vendor email, "no money moved" to the buyer. After the
 * switch to LIVE keys such an order may still be PENDING, and paying it would
 * take real money for an order the rest of the system treats as a rehearsal.
 * CheckoutPreparation refuses to start that payment; PaymentEventService is
 * the backstop for a checkout opened just before the switch.
 */
@Testcontainers
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class TestOrderLiveModeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Production's setting with a live-shaped key: checkout is open.
        registry.add("app.payments.test-mode-checkout", () -> "admins-only");
        registry.add("app.stripe.secret-key", () -> "sk_live_placeholder_for_tests");
    }

    private static final ShippingAddressRequest ADDRESS = new ShippingAddressRequest(
            "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
            null, "Gqeberha", "Eastern Cape", "6001");

    @Autowired OrderService                orderService;
    @Autowired OrderRepository             orderRepository;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired CheckoutPreparation         checkoutPreparation;
    @Autowired VendorPayoutEntryRepository entryRepository;
    @Autowired TestFixtures                fixtures;
    @Autowired PlatformTransactionManager  transactionManager;
    @Autowired JdbcTemplate                jdbcTemplate;

    private record Placed(Long orderId, Long buyerId) {}

    /** An order left PENDING from before the switch: flagged test_order as the guard would have. */
    private Placed pendingTestOrder(String prefix) {
        String tag = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User vendor = fixtures.vendor("tolm-vendor-" + tag);
        Product p = fixtures.productForVendor("TOLM Item " + tag, "SKU-TOLM-" + tag,
                new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf("tolm-buyer-" + tag, p);
        Long orderId = orderService.placeOrder(buyer.getId()).id();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                jdbcTemplate.update("UPDATE orders SET test_order = true WHERE id = ?", orderId));
        return new Placed(orderId, buyer.getId());
    }

    @Test
    void startingALivePaymentForATestOrder_isRefused_andWritesNothing() {
        Placed placed = pendingTestOrder("PAY");

        assertThatThrownBy(() -> checkoutPreparation.attachShipping(placed.orderId(), placed.buyerId(), ADDRESS))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("test mode");

        var order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getAddressLine1()).isNull();
    }

    @Test
    void liveMoneyForATestOrder_isARefund_notAQuietNonSale(CapturedOutput output) {
        Placed placed = pendingTestOrder("HOOK");
        long cents = orderRepository.findById(placed.orderId()).orElseThrow().getTotalAmount()
                .multiply(BigDecimal.valueOf(100)).longValueExact();

        paymentEventService.handleCheckoutCompleted(placed.orderId(), "Paystack",
                new ProviderPayment("ERY-" + placed.orderId() + "-live00000000", cents, "ZAR"));

        assertThat(orderRepository.findById(placed.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(entryRepository.findByOrderId(placed.orderId())).isEmpty();
        assertThat(output.getAll())
                .contains("FOR TEST ORDER " + placed.orderId() + " ")
                .contains("MANUAL REFUND REQUIRED");
    }
}
