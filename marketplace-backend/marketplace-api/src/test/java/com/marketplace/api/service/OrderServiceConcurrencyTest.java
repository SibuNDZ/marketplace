package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.EmptyCartException;
import com.marketplace.api.exception.OrderExceptions.InsufficientStockException;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.ProductRepository;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE test that justifies TestContainers in this project. H2's lock semantics
 * differ from PostgreSQL's, so a passing H2 concurrency test proves nothing.
 *
 * Scenario: a product has stock 1. Two users race to buy it simultaneously.
 * Exactly one must succeed, the other must get InsufficientStockException,
 * and final stock must be exactly 0 — never -1 (oversell).
 */
@Testcontainers
@SpringBootTest
class OrderServiceConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // No ddl-auto override — Flyway runs the real migrations against this
        // TestContainers database, making the test suite also a migration test.
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired CartRepository cartRepository;
    @Autowired TestFixtures fixtures;

    @Test
    void concurrentCheckout_ofLastUnit_sellsExactlyOnce() throws Exception {
        Product scarce = fixtures.product("Last Widget", "SKU-LAST-1", new BigDecimal("199.99"), 1);
        User alice = fixtures.customerWithCart("alice", scarce, 1);
        User bob   = fixtures.customerWithCart("bob",   scarce, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger stockFailures = new AtomicInteger();

        java.util.function.Function<Long, Callable<Void>> checkout = userId -> () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                orderService.placeOrder(userId);
                successes.incrementAndGet();
            } catch (InsufficientStockException e) {
                stockFailures.incrementAndGet();
            }
            return null;
        };

        List<Future<Void>> futures = pool.invokeAll(
                List.of(checkout.apply(alice.getId()), checkout.apply(bob.getId())),
                30, TimeUnit.SECONDS);
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(stockFailures.get()).isEqualTo(1);
        assertThat(productRepository.findById(scarce.getId()).orElseThrow().getStock())
                .isZero();
    }

    @Test
    void cancellation_restoresStock() {
        Product product = fixtures.product("Widget", "SKU-W-2", new BigDecimal("50.00"), 10);
        User user = fixtures.customerWithCart("carol", product, 3);

        OrderResponse placed = orderService.placeOrder(user.getId());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(7);

        orderService.cancelOrder(placed.id(), user.getId());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(10);
    }

    @Test
    void placedOrder_snapshotsPrice_immuneToLaterPriceChange() {
        Product product = fixtures.product("Gadget", "SKU-G-2", new BigDecimal("100.00"), 5);
        User user = fixtures.customerWithCart("dave", product, 1);

        OrderResponse placed = orderService.placeOrder(user.getId());

        Product p = productRepository.findById(product.getId()).orElseThrow();
        p.setPrice(new BigDecimal("200.00"));
        productRepository.save(p);

        OrderResponse reloaded = orderService.getOrder(placed.id(), user.getId());
        assertThat(reloaded.items().get(0).unitPrice()).isEqualByComparingTo("100.00");
        assertThat(reloaded.total()).isEqualByComparingTo("100.00");
    }

    /**
     * Cart-level pessimistic lock prevents double-submit: two concurrent placeOrder
     * calls for the same user must produce exactly one order. The second call
     * blocks on the cart lock, then sees the cleared cart and throws EmptyCartException.
     * Stock is decremented exactly once — no oversell, no phantom duplicate order.
     */
    @Test
    void sameUser_doubleSubmit_createsExactlyOneOrder() throws Exception {
        Product item = fixtures.product("Double-Submit Widget", "SKU-DS-1", new BigDecimal("50.00"), 5);
        User user = fixtures.customerWithCart("ds-user", item, 2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger emptyCartFailures = new AtomicInteger();

        Callable<Void> checkout = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                orderService.placeOrder(user.getId());
                successes.incrementAndGet();
            } catch (EmptyCartException e) {
                emptyCartFailures.incrementAndGet();
            }
            return null;
        };

        List<Future<Void>> futures = pool.invokeAll(List.of(checkout, checkout), 30, TimeUnit.SECONDS);
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(emptyCartFailures.get()).isEqualTo(1);

        // Exactly one order was created
        assertThat(orderRepository.findByUserId(user.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements()).isEqualTo(1);

        // Stock decremented exactly once (2 items bought once from stock-5 product)
        assertThat(productRepository.findById(item.getId()).orElseThrow().getStock()).isEqualTo(3);
    }
}
