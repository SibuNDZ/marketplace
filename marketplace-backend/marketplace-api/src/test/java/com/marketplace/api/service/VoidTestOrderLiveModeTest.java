package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that makes voiding safe at all: with LIVE payment credentials an
 * order may hold real money, so no order can be voided. A separate class
 * because the payment mode is read once at boot.
 */
@Testcontainers
@SpringBootTest
class VoidTestOrderLiveModeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // A live-shaped key. Nothing here calls Stripe; only the prefix is read.
        registry.add("app.stripe.secret-key", () -> "sk_live_placeholder_for_tests");
    }

    @Autowired OrderService        orderService;
    @Autowired PaymentEventService paymentEventService;
    @Autowired OrderRepository     orderRepository;
    @Autowired TestFixtures        fixtures;

    @Test
    void liveModeRefusesEveryVoid() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User vendor = fixtures.vendor("live-vendor-" + tag);
        Product p = fixtures.productForVendor("Live Item " + tag, "SKU-LIVE-" + tag,
                new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf("live-buyer-" + tag, p);
        OrderResponse order = orderService.placeOrder(buyer.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Stripe");
        User admin = fixtures.admin("live-admin-" + tag);

        assertThatThrownBy(() -> orderService.voidTestOrder(order.id(), admin.getId(), "test"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("refunded instead");
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }
}
