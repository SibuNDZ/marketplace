package com.marketplace.api.email;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The email half of the checkout guard, in this package because
 * EmailService.send is package-private (as in OrderEmailFlowTest).
 *
 * This is the exact failure that prompted the guard: a vendor emailed "a
 * customer paid" for an owner's test checkout. With the guard on, the admin's
 * own confirmation says TEST in the subject and body, and no vendor hears a
 * thing.
 */
@Testcontainers
@SpringBootTest
class TestOrderEmailTest {

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

    @Autowired OrderService        orderService;
    @Autowired PaymentEventService paymentEventService;
    @Autowired TestFixtures        fixtures;
    @MockitoBean EmailService      emailService;

    @Test
    void testOrderEmailsTheAdminAsATestAndNeverTheVendor() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User vendor = fixtures.vendor("email-vendor-" + tag);
        Product p = fixtures.productForVendor("Email Item " + tag, "SKU-TE-" + tag,
                new BigDecimal("190.00"), 5, vendor);
        User admin = fixtures.admin("email-admin-" + tag);
        fixtures.addToCart(admin.getId(), p, 1);

        OrderResponse order = orderService.placeOrder(admin.getId());
        paymentEventService.handleCheckoutCompleted(order.id(), "Yoco");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).send(eq(admin.getEmail()), subject.capture(), html.capture());
        assertThat(subject.getValue()).startsWith("[TEST]");
        assertThat(html.getValue())
                .contains("TEST order")
                .contains("vendors were NOT notified")
                .doesNotContain("the vendors have been notified");

        verify(emailService, after(2000).never()).send(eq(vendor.getEmail()), anyString(), anyString());
    }
}
