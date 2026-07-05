package com.marketplace.api.payment;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

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

    @Test
    void completedEvent_transitionsToPaid_withHistory() {
        Product product = fixtures.product("Pay-Widget 1", "SKU-PE-1", new BigDecimal("49.99"), 3);
        User buyer = fixtures.customerWithCart("pe-buyer1", product, 1);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId);

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
        paymentEventService.handleCheckoutCompleted(orderId);
        paymentEventService.handleCheckoutCompleted(orderId); // duplicate delivery

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
        paymentEventService.handleCheckoutCompleted(orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
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
