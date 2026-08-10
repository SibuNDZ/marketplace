package com.marketplace.api.email;

import com.marketplace.api.entity.Cart;
import com.marketplace.api.entity.CartItem;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.CategoryRepository;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.service.OrderAdminService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.OrderStatusRecorder;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * Integration tests for the order email pipeline: recorder event -> after-commit
 * async listener -> OrderEmailService composition -> EmailService transport.
 *
 * The TRANSPORT is mocked (the seam to Resend); everything upstream of it runs
 * real, so these tests cover the listener wiring, the reload with the full
 * fetch graph, per-vendor grouping, and template composition.
 *
 * Not @Transactional: the listener runs after COMMIT on another thread, so
 * fixtures must actually commit (same reasoning as OrderServiceConcurrencyTest).
 */
@Testcontainers
@SpringBootTest
class OrderEmailFlowTest {

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

    @MockitoBean EmailService emailService;

    @Autowired TestFixtures         fixtures;
    @Autowired OrderService         orderService;
    @Autowired OrderAdminService    orderAdminService;
    @Autowired PaymentEventService  paymentEventService;
    @Autowired OrderRepository      orderRepository;
    @Autowired ProductRepository    productRepository;
    @Autowired CartRepository       cartRepository;
    @Autowired CategoryRepository   categoryRepository;
    @Autowired OrderStatusRecorder  recorder;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void paidTransition_emailsBuyerAndEachVendor_withOnlyTheirItems() {
        Product p1 = fixtures.product("Mail-Widget A", "SKU-MAIL-A", new BigDecimal("100.00"), 5);
        User vendor2 = fixtures.vendor("mail-vendor2");
        Product p2 = secondVendorProduct(vendor2);
        User buyer = buyerWithTwoProductCart("mail-buyer1", p1, p2);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        setShippingAddress(orderId);

        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");

        ArgumentCaptor<String> buyerHtml = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).send(
                eq(buyer.getEmail()), contains("is confirmed"), buyerHtml.capture());
        assertThat(buyerHtml.getValue())
                .contains("Mail-Widget A").contains("Mail-Widget B")
                .contains("12 Milkwood Lane");

        // Vendor 1 is the shared fixture vendor; sees ONLY their item plus the address.
        ArgumentCaptor<String> v1Html = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).send(
                eq("test-vendor@test.local"), contains("New paid order"), v1Html.capture());
        assertThat(v1Html.getValue())
                .contains("Mail-Widget A")
                .doesNotContain("Mail-Widget B")
                .contains("Ship to").contains("12 Milkwood Lane");

        ArgumentCaptor<String> v2Html = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).send(
                eq(vendor2.getEmail()), contains("New paid order"), v2Html.capture());
        assertThat(v2Html.getValue())
                .contains("Mail-Widget B")
                .doesNotContain("Mail-Widget A");
    }

    @Test
    void shippedTransition_emailsBuyer_withTrackingNumber() {
        Product product = fixtures.product("Mail-Ship 1", "SKU-MAIL-S1", new BigDecimal("50.00"), 5);
        User buyer = fixtures.customerWithCart("mail-buyer2", product, 1);
        User admin = fixtures.admin("mail-admin1");

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");
        orderAdminService.transition(orderId, OrderStatus.SHIPPED, admin.getId(),
                "Shipped", "TRK-MAIL-777");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).send(
                eq(buyer.getEmail()), contains("has shipped"), html.capture());
        assertThat(html.getValue()).contains("Tracking number").contains("TRK-MAIL-777");
    }

    @Test
    void transportFailure_neverAffectsTheTransition() {
        doThrow(new RuntimeException("simulated Resend outage"))
                .when(emailService).send(anyString(), anyString(), anyString());

        Product product = fixtures.product("Mail-Fault 1", "SKU-MAIL-F1", new BigDecimal("75.00"), 5);
        User buyer = fixtures.customerWithCart("mail-buyer3", product, 1);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe");

        // The send was attempted (and blew up) on the async thread...
        verify(emailService, timeout(5000)).send(eq(buyer.getEmail()), anyString(), anyString());
        // ...while the transition committed untouched.
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void rolledBackTransition_sendsNothing() {
        Product product = fixtures.product("Mail-Rollback 1", "SKU-MAIL-R1", new BigDecimal("60.00"), 5);
        User buyer = fixtures.customerWithCart("mail-buyer4", product, 1);
        Long orderId = orderService.placeOrder(buyer.getId()).id();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
            order.setStatus(OrderStatus.PAID);
            recorder.record(order, OrderStatus.PENDING, OrderStatus.PAID,
                    buyer.getId(), "rolled back on purpose");
            status.setRollbackOnly();
        });

        // AFTER_COMMIT must mean exactly that: a rolled-back PAID sends no mail.
        verify(emailService, after(2000).never()).send(any(), any(), any());
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
    }

    /** Mirrors TestFixtures.product but with a caller-supplied vendor. */
    private Product secondVendorProduct(User vendor) {
        Product p = new Product();
        p.setName("Mail-Widget B");
        p.setSku("SKU-MAIL-B");
        p.setPrice(new BigDecimal("200.00"));
        p.setStock(5);
        p.setVendor(vendor);
        p.setCategory(categoryRepository.findBySlug("other").orElseThrow());
        return productRepository.save(p);
    }

    /** Mirrors TestFixtures.customerWithCart but with two single-quantity items. */
    private User buyerWithTwoProductCart(String username, Product p1, Product p2) {
        User buyer = fixtures.customer(username);
        Cart cart = new Cart();
        cart.setUser(buyer);
        for (Product p : new Product[]{p1, p2}) {
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setProduct(p);
            item.setQuantity(1);
            cart.getItems().add(item);
        }
        cartRepository.save(cart);
        return buyer;
    }

    private void setShippingAddress(Long orderId) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            order.setRecipientName("Thandi Mokoena");
            order.setPhone("+27 82 000 0000");
            order.setAddressLine1("12 Milkwood Lane");
            order.setCity("Gqeberha");
            order.setProvince("Eastern Cape");
            order.setPostalCode("6001");
        });
    }
}
