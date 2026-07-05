package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.InsufficientStockException;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for soft-delete and stock-delta — the two "products stop
 * lying" debts. Uses TestContainers so Flyway runs V1–V8 on every run; this
 * test suite is also a migration test for V8.
 */
@Testcontainers
@SpringBootTest
class ProductLifecycleTest {

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

    @Autowired ProductService         productService;
    @Autowired ProductStockService    productStockService;
    @Autowired ProductRepository      productRepository;
    @Autowired OrderService           orderService;
    @Autowired OrderRepository        orderRepository;
    @Autowired UserRepository         userRepository;
    @Autowired TestFixtures           fixtures;

    // ── helpers ─────────────────────────────────────────────────────────

    private UserPrincipal principalFor(User user) {
        return UserPrincipal.from(user);
    }

    // ── soft-delete tests ────────────────────────────────────────────────

    @Test
    void softDelete_hidesFromCatalog_keepsOrderHistory() {
        Product product = fixtures.product("SD Widget", "SKU-SD-1", new BigDecimal("19.99"), 5);
        User buyer = fixtures.customerWithCart("sd-buyer1", product, 1);
        User admin = fixtures.admin("sd-admin1");

        // Buy and deliver the product
        OrderResponse order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());

        // Vendor soft-deletes the product
        User vendor = userRepository.findByEmail("test-vendor@test.local").orElseThrow();
        productService.delete(product.getId(), principalFor(vendor));

        // Catalog hides it — public GET returns 404
        assertThatThrownBy(() -> productService.get(product.getId(), null))
                .hasMessageContaining(product.getId().toString());

        // Listing excludes it
        assertThat(productService.list(PageRequest.of(0, 100)).getContent())
                .noneMatch(p -> p.id().equals(product.getId()));

        // BUT order history still shows the line — that's the whole point
        OrderResponse history = orderService.getOrder(order.id(), buyer.getId());
        assertThat(history.items()).hasSize(1);
        assertThat(history.items().get(0).productName()).isEqualTo("SD Widget");
        assertThat(history.items().get(0).unitPrice()).isEqualByComparingTo("19.99");
    }

    @Test
    void deletedProduct_inCart_failsCheckoutAsShortage() {
        Product product = fixtures.product("SD Widget 2", "SKU-SD-2", new BigDecimal("9.99"), 5);
        User buyer = fixtures.customerWithCart("sd-buyer2", product, 2);

        // Soft-delete while item is in cart
        User vendor = userRepository.findByEmail("test-vendor@test.local").orElseThrow();
        productService.delete(product.getId(), principalFor(vendor));

        // Checkout should fail — deleted product becomes a "no longer available" shortage
        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(ex -> {
                    InsufficientStockException ise = (InsufficientStockException) ex;
                    assertThat(ise.getShortages()).hasSize(1);
                    assertThat(ise.getShortages().get(0).productName())
                            .contains("no longer available");
                });

        // Stock untouched (checkout rolled back fully)
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(5);
    }

    @Test
    void deletedSku_reusable() {
        // Create, delete, then relist with the same SKU — partial unique index allows it.
        Product first = fixtures.product("Relist Widget", "SKU-RELIST-1", new BigDecimal("5.00"), 3);
        User vendor = userRepository.findByEmail("test-vendor@test.local").orElseThrow();
        productService.delete(first.getId(), principalFor(vendor));

        // Creating a second product with the same SKU must succeed
        Product second = fixtures.product("Relist Widget v2", "SKU-RELIST-1", new BigDecimal("6.00"), 10);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(productRepository.findByIdAndDeletedAtIsNull(second.getId())).isPresent();
    }

    // ── stock-delta tests ────────────────────────────────────────────────

    @Test
    void stockDelta_adjusts_andFloorsAtZero() {
        Product product = fixtures.product("Delta Widget", "SKU-DELTA-1", new BigDecimal("10.00"), 5);
        User admin = fixtures.admin("delta-admin1");
        UserPrincipal adminPrincipal = principalFor(admin);

        // Positive delta: stock increases
        int after = productStockService.adjustStock(product.getId(), 50, adminPrincipal);
        assertThat(after).isEqualTo(55);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(55);

        // Negative delta that would go below zero: rejected
        assertThatThrownBy(() -> productStockService.adjustStock(product.getId(), -100, adminPrincipal))
                .isInstanceOf(ProductStockService.InsufficientAdjustmentException.class);

        // Stock unchanged after the failed adjustment
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(55);
    }

    @Test
    void stockDelta_ownership() {
        Product product = fixtures.product("Owned Widget", "SKU-OWN-1", new BigDecimal("10.00"), 5);
        User otherVendor = fixtures.vendor("other-stock-vendor");
        UserPrincipal otherPrincipal = principalFor(otherVendor);

        // A vendor who doesn't own this product cannot adjust its stock
        assertThatThrownBy(() -> productStockService.adjustStock(product.getId(), 10, otherPrincipal))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(5);
    }

    @Test
    void stockDelta_concurrentWithCheckout() throws Exception {
        // Stock 5. Thread A buys all 5 (checkout). Thread B adjusts -3.
        // Under pessimistic locks, at most one can succeed without going negative.
        // Final stock must be >= 0 and reconcile with what actually committed.
        Product item = fixtures.product("Conc Widget", "SKU-CONC-1", new BigDecimal("10.00"), 5);
        User buyer = fixtures.customerWithCart("conc-buyer1", item, 5);
        User admin = fixtures.admin("conc-admin1");
        UserPrincipal adminPrincipal = principalFor(admin);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger checkoutSuccess = new AtomicInteger();
        AtomicInteger adjustSuccess  = new AtomicInteger();

        Callable<Void> checkout = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                orderService.placeOrder(buyer.getId());
                checkoutSuccess.incrementAndGet();
            } catch (InsufficientStockException e) {
                // expected if adjustment won the race
            }
            return null;
        };

        Callable<Void> adjust = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                productStockService.adjustStock(item.getId(), -3, adminPrincipal);
                adjustSuccess.incrementAndGet();
            } catch (ProductStockService.InsufficientAdjustmentException e) {
                // expected if checkout won the race
            }
            return null;
        };

        List<Future<Void>> futures = pool.invokeAll(List.of(checkout, adjust), 30, TimeUnit.SECONDS);
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        int finalStock = productRepository.findById(item.getId()).orElseThrow().getStock();
        assertThat(finalStock).isGreaterThanOrEqualTo(0);

        // Arithmetic consistency: stock = initial 5 − units_sold + delta_applied
        int unitsSold = checkoutSuccess.get() * 5;
        int deltaApplied = adjustSuccess.get() * (-3);
        assertThat(finalStock).isEqualTo(5 + deltaApplied - unitsSold);
    }
}
