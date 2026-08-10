package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buyer-facing order tabs. The rules under test are that each tab shows
 * exactly its statuses, that "Returns & cancelled" spans two, and that tabs
 * never widen a buyer's view beyond their own orders.
 */
@Testcontainers
@SpringBootTest
class OrderTabsTest {

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

    @Autowired OrderService        orderService;
    @Autowired OrderAdminService   orderAdminService;
    @Autowired PaymentEventService paymentEventService;
    @Autowired OrderRepository     orderRepository;
    @Autowired TestFixtures        fixtures;

    private List<Long> idsIn(Long userId, OrderTab tab) {
        return orderService.getMyOrders(userId, tab, PageRequest.of(0, 50))
                .getContent().stream().map(OrderResponse::id).toList();
    }

    @Test
    void eachTabShowsExactlyItsStatuses_andReturnsSpansCancelledAndRefunded() {
        User buyer = fixtures.customer("tabs-buyer");
        User admin = fixtures.admin("tabs-admin");

        // One order per state we care about, each from its own cart.
        Product p1 = fixtures.product("Tab-Pending", "SKU-TAB-1", new BigDecimal("10.00"), 20);
        Long pending = orderService.placeOrder(
                fixtures.customerWithCartOf("tabs-b1", p1).getId()).id();

        Product p2 = fixtures.product("Tab-Paid", "SKU-TAB-2", new BigDecimal("10.00"), 20);
        Long paid = orderService.placeOrder(
                fixtures.customerWithCartOf("tabs-b2", p2).getId()).id();
        paymentEventService.handleCheckoutCompleted(paid, "Stripe");

        Product p3 = fixtures.product("Tab-Shipped", "SKU-TAB-3", new BigDecimal("10.00"), 20);
        Long shipped = orderService.placeOrder(
                fixtures.customerWithCartOf("tabs-b3", p3).getId()).id();
        paymentEventService.handleCheckoutCompleted(shipped, "Stripe");
        orderAdminService.transition(shipped, OrderStatus.SHIPPED, admin.getId(), "s");

        Product p4 = fixtures.product("Tab-Delivered", "SKU-TAB-4", new BigDecimal("10.00"), 20);
        Long delivered = orderService.placeOrder(
                fixtures.customerWithCartOf("tabs-b4", p4).getId()).id();
        paymentEventService.handleCheckoutCompleted(delivered, "Stripe");
        orderAdminService.transition(delivered, OrderStatus.SHIPPED, admin.getId(), "s");
        orderAdminService.transition(delivered, OrderStatus.DELIVERED, admin.getId(), "d");

        // Each of the above belongs to its own buyer; re-read per owner.
        assertThat(statusOf(pending)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(paid)).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(shipped)).isEqualTo(OrderStatus.SHIPPED);
        assertThat(statusOf(delivered)).isEqualTo(OrderStatus.DELIVERED);

        Long ownerOfPending = ownerOf(pending);
        assertThat(idsIn(ownerOfPending, OrderTab.UNPAID)).contains(pending);
        assertThat(idsIn(ownerOfPending, OrderTab.PROCESSING)).doesNotContain(pending);
        assertThat(idsIn(ownerOf(paid), OrderTab.PROCESSING)).contains(paid);
        assertThat(idsIn(ownerOf(shipped), OrderTab.SHIPPED)).contains(shipped);
        assertThat(idsIn(ownerOf(delivered), OrderTab.DELIVERED)).contains(delivered);

        // Returns tab spans CANCELLED and REFUNDED.
        Product p5 = fixtures.product("Tab-Cancelled", "SKU-TAB-5", new BigDecimal("10.00"), 20);
        User b5 = fixtures.customerWithCartOf("tabs-b5", p5);
        Long cancelled = orderService.placeOrder(b5.getId()).id();
        orderService.cancelOrder(cancelled, b5.getId());

        Product p6 = fixtures.product("Tab-Refunded", "SKU-TAB-6", new BigDecimal("10.00"), 20);
        User b6 = fixtures.customerWithCartOf("tabs-b6", p6);
        Long refunded = orderService.placeOrder(b6.getId()).id();
        paymentEventService.handleCheckoutCompleted(refunded, "Stripe");
        orderAdminService.transition(refunded, OrderStatus.SHIPPED, admin.getId(), "s");
        orderAdminService.transition(refunded, OrderStatus.DELIVERED, admin.getId(), "d");
        orderAdminService.transition(refunded, OrderStatus.REFUNDED, admin.getId(), "r");

        assertThat(idsIn(b5.getId(), OrderTab.RETURNS)).contains(cancelled);
        assertThat(idsIn(b6.getId(), OrderTab.RETURNS)).contains(refunded);
        // ...and neither shows up under an active-order tab.
        assertThat(idsIn(b5.getId(), OrderTab.PROCESSING)).doesNotContain(cancelled);
        assertThat(idsIn(b6.getId(), OrderTab.DELIVERED)).doesNotContain(refunded);
    }

    @Test
    void allTab_matchesUnfilteredList_andCountsAgree() {
        Product p = fixtures.product("Tab-Counts", "SKU-TAB-C", new BigDecimal("10.00"), 20);
        User buyer = fixtures.customerWithCartOf("tabs-counter", p);
        Long first = orderService.placeOrder(buyer.getId()).id();

        // A second, paid order for the same buyer.
        fixtures.addToCart(buyer.getId(), p, 1);
        Long second = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(second, "Stripe");

        List<Long> all = idsIn(buyer.getId(), OrderTab.ALL);
        assertThat(all).contains(first, second);
        // ALL must equal the legacy unfiltered call, or the tab row and the
        // old list would disagree about what "your orders" means.
        assertThat(all).containsExactlyElementsOf(
                orderService.getMyOrders(buyer.getId(), PageRequest.of(0, 50))
                        .getContent().stream().map(OrderResponse::id).toList());

        Map<OrderTab, Long> counts = orderService.getMyOrderCounts(buyer.getId());
        assertThat(counts.get(OrderTab.ALL)).isEqualTo(2);
        assertThat(counts.get(OrderTab.UNPAID)).isEqualTo(1);
        assertThat(counts.get(OrderTab.PROCESSING)).isEqualTo(1);
        assertThat(counts.get(OrderTab.RETURNS)).isZero();
        // The badge row must never disagree with the tab it labels.
        for (OrderTab tab : OrderTab.values()) {
            assertThat(counts.get(tab))
                    .as("count for %s matches the tab contents", tab)
                    .isEqualTo((long) idsIn(buyer.getId(), tab).size());
        }
    }

    @Test
    void tabsNeverLeakAnotherBuyersOrders() {
        Product p = fixtures.product("Tab-Privacy", "SKU-TAB-P", new BigDecimal("10.00"), 20);
        User mine = fixtures.customerWithCartOf("tabs-mine", p);
        User theirs = fixtures.customerWithCartOf("tabs-theirs", p);

        Long myOrder = orderService.placeOrder(mine.getId()).id();
        Long theirOrder = orderService.placeOrder(theirs.getId()).id();

        for (OrderTab tab : OrderTab.values()) {
            assertThat(idsIn(mine.getId(), tab))
                    .as("tab %s must not expose another buyer's order", tab)
                    .doesNotContain(theirOrder);
        }
        assertThat(idsIn(mine.getId(), OrderTab.UNPAID)).contains(myOrder);
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private Long ownerOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getUser().getId();
    }
}
