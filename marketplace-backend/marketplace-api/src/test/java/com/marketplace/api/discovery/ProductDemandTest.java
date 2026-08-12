package com.marketplace.api.discovery;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.service.OrderAdminService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The urgency line ships INERT, so tests are the only place its behaviour is
 * observable. Production returns null for every product and will keep doing
 * so until real order volume exists — that is the design, not a defect, and
 * it means nobody can eyeball this feature to check it works.
 *
 * The threshold is pinned at 3 here rather than inherited from config, so
 * tuning DEMAND_MIN_BUYERS in production cannot silently rewrite what these
 * tests claim.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "app.discovery.demand.min-buyers=3")
class ProductDemandTest {

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

    @Autowired TestFixtures         fixtures;
    @Autowired OrderService         orderService;
    @Autowired OrderAdminService    orderAdminService;
    @Autowired ProductDemandService demandService;
    @Autowired JdbcTemplate         jdbc;
    @Autowired TransactionTemplate  txTemplate;

    private static int seq = 0;
    private static String uniq(String base) { return base + "-" + (++seq); }

    /** One distinct buyer, paid through to DELIVERED. */
    private void buy(Product product, String buyerName, int qty) {
        User admin = fixtures.admin(uniq("dm-admin"));
        User buyer = fixtures.customerWithCart(uniq(buyerName), product, qty);
        var order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());
    }

    @Test
    @DisplayName("silence below the threshold: two buyers is not a crowd")
    void belowThresholdReturnsNull() {
        Product p = fixtures.product(uniq("Demand Below"), uniq("SKU-DB"), new BigDecimal("10"), 100);
        buy(p, "dm-b1", 1);
        buy(p, "dm-b2", 1);

        assertThat(demandService.recentBuyers(p.getId())).isNull();
    }

    @Test
    @DisplayName("the line appears exactly at the threshold, with the true count")
    void atThresholdReturnsExactCount() {
        Product p = fixtures.product(uniq("Demand At"), uniq("SKU-DA"), new BigDecimal("10"), 100);
        buy(p, "dm-a1", 1);
        buy(p, "dm-a2", 1);
        buy(p, "dm-a3", 1);

        // Not rounded, bucketed or padded — three people means three.
        assertThat(demandService.recentBuyers(p.getId())).isEqualTo(3L);
    }

    @Test
    @DisplayName("it counts people, not orders and not units")
    void repeatBuyerCountsOnce() {
        Product p = fixtures.product(uniq("Demand Repeat"), uniq("SKU-DR"), new BigDecimal("10"), 100);
        User admin = fixtures.admin(uniq("dm-admin"));

        // One person, three separate orders, twenty units. Still one person,
        // so the sentence "N people bought this" stays true.
        User loyal = fixtures.customerWithCart(uniq("dm-loyal"), p, 10);
        var first = orderService.placeOrder(loyal.getId());
        fixtures.deliverOrder(first.id(), admin.getId());

        fixtures.addToCart(loyal.getId(), p, 5);
        var second = orderService.placeOrder(loyal.getId());
        fixtures.deliverOrder(second.id(), admin.getId());

        fixtures.addToCart(loyal.getId(), p, 5);
        var third = orderService.placeOrder(loyal.getId());
        fixtures.deliverOrder(third.id(), admin.getId());

        assertThat(demandService.recentBuyers(p.getId())).isNull();
    }

    @Test
    @DisplayName("a vendor cannot manufacture demand for their own listing")
    void vendorSelfPurchaseExcluded() {
        User vendor = fixtures.vendor(uniq("dm-vendor"));
        Product p = fixtures.productForVendor(uniq("Demand Self"), uniq("SKU-DS"),
                new BigDecimal("10"), 100, vendor);
        User admin = fixtures.admin(uniq("dm-admin"));

        buy(p, "dm-s1", 1);
        buy(p, "dm-s2", 1);

        // The vendor buys their own product as the would-be third buyer.
        fixtures.addToCart(vendor.getId(), p, 1);
        var selfOrder = orderService.placeOrder(vendor.getId());
        fixtures.deliverOrder(selfOrder.id(), admin.getId());

        // Three humans bought it, but only two of them are evidence of demand.
        assertThat(demandService.recentBuyers(p.getId())).isNull();
    }

    @Test
    @DisplayName("only money that arrived counts")
    void unpaidAndCancelledExcluded() {
        Product p = fixtures.product(uniq("Demand Unpaid"), uniq("SKU-DU"), new BigDecimal("10"), 100);
        User admin = fixtures.admin(uniq("dm-admin"));

        buy(p, "dm-u1", 1);

        // PENDING: no money ever arrived.
        User pending = fixtures.customerWithCart(uniq("dm-u2"), p, 1);
        orderService.placeOrder(pending.getId());

        // CANCELLED: aborted before payment. Goes through cancelOrder, not
        // the admin transition — OrderAdminService refuses CANCELLED outright
        // because cancelling has to restore stock.
        User cancelled = fixtures.customerWithCart(uniq("dm-u3"), p, 1);
        var cancelledOrder = orderService.placeOrder(cancelled.getId());
        orderService.cancelOrder(cancelledOrder.id(), cancelled.getId());

        assertThat(demandService.recentBuyers(p.getId())).isNull();
    }

    @Test
    @DisplayName("the window really is 24 hours, not all time")
    void olderThan24hExcluded() {
        Product p = fixtures.product(uniq("Demand Window"), uniq("SKU-DW"), new BigDecimal("10"), 100);
        buy(p, "dm-w1", 1);
        buy(p, "dm-w2", 1);
        buy(p, "dm-w3", 1);

        // Three buyers today clears the bar.
        assertThat(demandService.recentBuyers(p.getId())).isEqualTo(3L);

        // Age every one of those orders past the window. Backdating in SQL is
        // the only way to test this: the app has no clock seam, and a test
        // that cannot age data would silently assert "all time" forever.
        //
        // The TransactionTemplate is REQUIRED, not tidiness. This datasource
        // runs hikari.auto-commit: false, so a bare JdbcTemplate.update here
        // executes, reports a row count, and is silently discarded when the
        // connection returns to the pool — the exact bug that shipped the
        // embedding sweep broken. Without it this test backdates nothing and
        // then "passes" the moment someone widens the window.
        int aged = txTemplate.execute(status -> jdbc.update("""
                UPDATE orders SET created_at = now() - INTERVAL '25 hours'
                WHERE id IN (SELECT order_id FROM order_items WHERE product_id = ?)
                """, p.getId()));
        assertThat(aged).isEqualTo(3);

        assertThat(demandService.recentBuyers(p.getId())).isNull();
    }

    @Test
    @DisplayName("demand is per product, not shared across the catalogue")
    void countIsScopedToTheProduct() {
        Product popular = fixtures.product(uniq("Demand Pop"), uniq("SKU-DP"), new BigDecimal("10"), 100);
        Product quiet   = fixtures.product(uniq("Demand Quiet"), uniq("SKU-DQ"), new BigDecimal("10"), 100);

        buy(popular, "dm-p1", 1);
        buy(popular, "dm-p2", 1);
        buy(popular, "dm-p3", 1);

        assertThat(demandService.recentBuyers(popular.getId())).isEqualTo(3L);
        assertThat(demandService.recentBuyers(quiet.getId())).isNull();
    }
}
