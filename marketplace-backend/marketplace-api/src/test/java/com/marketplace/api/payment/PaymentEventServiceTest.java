package com.marketplace.api.payment;

import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the payment event lifecycle.
 *
 * Tests aim directly at PaymentEventService — the webhook controller is a
 * thin HTTP/signature shell and is verified manually via Stripe CLI.
 * All scenarios use real PostgreSQL (TestContainers) so that the row-lock
 * semantics are identical to production.
 */
@Testcontainers
@SpringBootTest
class PaymentEventServiceTest {

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

    @Autowired PaymentEventService         paymentEventService;
    @Autowired OrderService                orderService;
    @Autowired OrderRepository             orderRepository;
    @Autowired ProductRepository           productRepository;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired TestFixtures                fixtures;
    @Autowired CheckoutPreparation         checkoutPreparation;
    @Autowired PlatformTransactionManager  transactionManager;
    @Autowired JdbcTemplate                jdbcTemplate;

    @Test
    void completedEvent_transitionsToPaid_withHistory() {
        Product product = fixtures.product("Pay-Widget 1", "SKU-PE-1", new BigDecimal("49.99"), 3);
        User buyer = fixtures.customerWithCart("pe-buyer1", product, 1);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        List<OrderStatusHistory> history =
                historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getFromStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(history.get(1).getToStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void duplicateEvent_idempotent() {
        Product product = fixtures.product("Pay-Widget 2", "SKU-PE-2", new BigDecimal("19.99"), 3);
        User buyer = fixtures.customerWithCart("pe-buyer2", product, 1);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe"); // duplicate delivery

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        // Only one PAID history row — idempotency means no double-write
        long paidRows = historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId)
                .stream().filter(h -> h.getToStatus() == OrderStatus.PAID).count();
        assertThat(paidRows).isEqualTo(1);
    }

    @Test
    void completedEvent_onCancelledOrder_doesNotChangeStatus() {
        Product product = fixtures.product("Pay-Widget 3", "SKU-PE-3", new BigDecimal("9.99"), 3);
        User buyer = fixtures.customerWithCart("pe-buyer3", product, 1);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        orderService.cancelOrder(orderId, buyer.getId());

        // Simulate webhook arriving after manual cancellation — should not throw,
        // should not change status (logs MANUAL REFUND REQUIRED server-side)
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * The webhook-vs-expiry race with the order ALREADY in the caller's
     * session, the way a controller under open-in-view has it after its own
     * findById. Another connection commits CANCELLED in between (the expiry
     * job). Without the refresh after the lock, handleCheckoutCompleted reads
     * the cached PENDING and flips a restocked CANCELLED order to PAID.
     */
    @Test
    void lockedCompletion_seesCancelCommittedAfterCallerLoadedTheOrder() {
        Product product = fixtures.product("Pay-Widget 5", "SKU-PE-5", new BigDecimal("39.99"), 3);
        User buyer = fixtures.customerWithCart("pe-buyer5", product, 1);
        Long orderId = orderService.placeOrder(buyer.getId()).id();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PENDING);
            commitOnOtherConnection("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", orderId);
            paymentEventService.handleCheckoutCompleted(orderId, "Paystack");
        });

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * Checkout writes the address and then waits on the provider call before
     * committing. If the expiry job cancels the order meanwhile, the checkout
     * commit must not write its stale PENDING back over CANCELLED: that would
     * leave a PENDING order whose stock was already restored, and the next
     * sweep would restore it a second time.
     */
    @Test
    void checkoutCommit_doesNotRevertAConcurrentCancel() {
        Product product = fixtures.product("Pay-Widget 6", "SKU-PE-6", new BigDecimal("39.99"), 3);
        User buyer = fixtures.customerWithCart("pe-buyer6", product, 1);
        Long orderId = orderService.placeOrder(buyer.getId()).id();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            checkoutPreparation.attachShipping(orderId, buyer.getId(), new ShippingAddressRequest(
                    "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
                    null, "Gqeberha", "Eastern Cape", "6001"));
            // ...the provider call would be in flight here...
            commitOnOtherConnection("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", orderId);
        });

        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getAddressLine1()).isEqualTo("12 Milkwood Lane");
    }

    /**
     * Commits on another connection while the caller's transaction stays
     * open: a separate thread has no transaction bound, so it gets its own.
     * The update runs inside its own transaction because Hikari hands out
     * connections with auto-commit off (application.yml); a bare
     * jdbcTemplate.update would be rolled back when its connection returned
     * to the pool, and the race under test would never happen.
     */
    private void commitOnOtherConnection(String sql, Long orderId) {
        try {
            CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                            .executeWithoutResult(tx -> jdbcTemplate.update(sql, orderId)))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void expiredPending_cancelledByJob_stockRestored() {
        Product product = fixtures.product("Pay-Widget 4", "SKU-PE-4", new BigDecimal("29.99"), 5);
        User buyer = fixtures.customerWithCart("pe-buyer4", product, 2);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        // Stock should be 3 after placing (5 - 2)
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);

        // Call cancelExpired directly (tests the method, not the @Scheduled timing)
        orderService.cancelExpired(orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        // Stock fully restored
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
    }
}
