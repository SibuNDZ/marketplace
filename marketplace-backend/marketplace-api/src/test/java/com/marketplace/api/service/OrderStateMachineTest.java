package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State machine correctness and audit-trail integrity.
 *
 * Uses TestContainers (real PostgreSQL) so that Flyway runs the actual
 * migrations — every test run is also a migration test for V1 + V2.
 */
@Testcontainers
@SpringBootTest
class OrderStateMachineTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    @Autowired OrderService orderService;
    @Autowired OrderAdminService orderAdminService;
    @Autowired PaymentEventService paymentEventService;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired TestFixtures fixtures;

    @Test
    void legalPath_recordsCompleteHistory() {
        Product widget = fixtures.product("Widget A", "SKU-SM-1", new BigDecimal("10.00"), 5);
        User customer  = fixtures.customerWithCart("sm-alice", widget, 1);
        User admin     = fixtures.admin("sm-admin");

        OrderResponse order = orderService.placeOrder(customer.getId());
        Long orderId = order.id();

        // New legal path: PENDING -> PAID (webhook) -> SHIPPED -> DELIVERED
        fixtures.deliverOrder(orderId, admin.getId());

        List<OrderStatusHistory> history =
                historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);

        assertThat(history).hasSize(4);

        // Row 0: creation event — from is null, changedBy is the customer
        assertThat(history.get(0).getFromStatus()).isNull();
        assertThat(history.get(0).getToStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(history.get(0).getChangedBy().getId()).isEqualTo(customer.getId());

        // Row 1: PENDING -> PAID by payment webhook
        assertThat(history.get(1).getFromStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(history.get(1).getToStatus()).isEqualTo(OrderStatus.PAID);

        // Row 2: PAID -> SHIPPED by admin
        assertThat(history.get(2).getFromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(history.get(2).getToStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(history.get(2).getChangedBy().getId()).isEqualTo(admin.getId());

        // Row 3: SHIPPED -> DELIVERED by admin
        assertThat(history.get(3).getFromStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(history.get(3).getToStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(history.get(3).getChangedBy().getId()).isEqualTo(admin.getId());
    }

    @Test
    void illegalTransition_rejected_andNoHistoryWritten() {
        Product widget = fixtures.product("Widget B", "SKU-SM-2", new BigDecimal("10.00"), 5);
        User customer  = fixtures.customerWithCart("sm-bob", widget, 1);
        User admin     = fixtures.admin("sm-admin2");

        OrderResponse order = orderService.placeOrder(customer.getId());
        Long orderId = order.id();

        // PENDING -> DELIVERED is illegal (skips SHIPPED)
        assertThatThrownBy(() ->
                orderAdminService.transition(orderId, OrderStatus.DELIVERED, admin.getId(), "oops"))
                .isInstanceOf(InvalidOrderStateException.class);

        // Audit atomicity: only the creation row exists; the failed transition wrote nothing
        List<OrderStatusHistory> history =
                historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getToStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancelledIsTerminal_furtherTransitionRejected() {
        Product widget = fixtures.product("Widget C", "SKU-SM-3", new BigDecimal("10.00"), 5);
        User customer  = fixtures.customerWithCart("sm-charlie", widget, 1);
        User admin     = fixtures.admin("sm-admin3");

        OrderResponse order = orderService.placeOrder(customer.getId());
        Long orderId = order.id();

        orderService.cancelOrder(orderId, customer.getId());

        assertThatThrownBy(() ->
                orderAdminService.transition(orderId, OrderStatus.SHIPPED, admin.getId(), "too late"))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void adminCancel_isRejected_withCancelEndpointHint() {
        // The CANCELLED guard fires before the order lookup, so no order seed needed.
        User admin = fixtures.admin("sm-admin4");

        assertThatThrownBy(() ->
                orderAdminService.transition(999L, OrderStatus.CANCELLED, admin.getId(), "admin cancel"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("cancel endpoint");
    }
}
