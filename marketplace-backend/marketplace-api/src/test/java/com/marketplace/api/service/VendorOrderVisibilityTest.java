package com.marketplace.api.service;

import com.marketplace.api.dto.VendorOrderDtos.VendorOrderResponse;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vendor visibility boundary, tested at the service layer where the
 * scoping decisions live. Controller wiring is a thin pass-through of the
 * token's user id; the @PreAuthorize role gate follows the same pattern
 * as the product endpoints.
 *
 * Not @Transactional: fixtures must commit (webhook/ship paths open their
 * own transactions), same reasoning as OrderServiceConcurrencyTest.
 */
@Testcontainers
@SpringBootTest
class VendorOrderVisibilityTest {

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

    @Autowired VendorOrderService          vendorOrderService;
    @Autowired OrderService                orderService;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired OrderRepository             orderRepository;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired TestFixtures                fixtures;
    @Autowired PlatformTransactionManager  txManager;

    @Test
    void pendingOrder_isInvisible_untilPaid() {
        User vendor = fixtures.vendor("vov-vendor1");
        Product p = fixtures.productForVendor("VOV-Alpha", "SKU-VOV-A",
                new BigDecimal("120.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf("vov-buyer1", p);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        setShippingAddress(orderId);

        assertThat(pageFor(vendor)).isEmpty();
        assertThatThrownBy(() -> vendorOrderService.get(vendor.getId(), orderId))
                .isInstanceOf(OrderNotFoundException.class);

        paymentEventService.handleCheckoutCompleted(orderId);

        Page<VendorOrderResponse> page = pageFor(vendor);
        assertThat(page.getContent()).hasSize(1);
        VendorOrderResponse view = page.getContent().get(0);
        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.shipTo()).isNotNull();
        assertThat(view.shipTo().addressLine1()).isEqualTo("7 Protea Road");
        assertThat(view.canShip()).isTrue();
    }

    @Test
    void vendorSeesOnlyTheirOwnItems_andMixedOrdersCannotBeVendorShipped() {
        User vendorA = fixtures.vendor("vov-vendorA");
        User vendorB = fixtures.vendor("vov-vendorB");
        Product pa = fixtures.productForVendor("VOV-Aardvark", "SKU-VOV-MA",
                new BigDecimal("100.00"), 5, vendorA);
        Product pb = fixtures.productForVendor("VOV-Bushbuck", "SKU-VOV-MB",
                new BigDecimal("300.00"), 5, vendorB);
        User buyer = fixtures.customerWithCartOf("vov-buyer2", pa, pb);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId);

        VendorOrderResponse aView = vendorOrderService.get(vendorA.getId(), orderId);
        assertThat(aView.items()).hasSize(1);
        assertThat(aView.items().get(0).productName()).isEqualTo("VOV-Aardvark");
        assertThat(aView.itemsTotal()).isEqualByComparingTo("100.00");

        // Both vendors see it; neither may ship it.
        assertThat(aView.canShip()).isFalse();
        assertThat(vendorOrderService.get(vendorB.getId(), orderId).canShip()).isFalse();
        assertThatThrownBy(() -> vendorOrderService.markShipped(vendorA.getId(), orderId))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("administrator");
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void singleVendorOrder_shipsAndRecordsHistory_andOtherVendorsGet404() {
        User vendor = fixtures.vendor("vov-vendor2");
        User stranger = fixtures.vendor("vov-vendor3");
        Product p = fixtures.productForVendor("VOV-Ship", "SKU-VOV-S",
                new BigDecimal("80.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf("vov-buyer3", p);

        Long orderId = orderService.placeOrder(buyer.getId()).id();
        paymentEventService.handleCheckoutCompleted(orderId);

        // A vendor with no items in the order cannot see or ship it, and the
        // failure is indistinguishable from a nonexistent order.
        assertThatThrownBy(() -> vendorOrderService.get(stranger.getId(), orderId))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> vendorOrderService.markShipped(stranger.getId(), orderId))
                .isInstanceOf(OrderNotFoundException.class);

        VendorOrderResponse shipped = vendorOrderService.markShipped(vendor.getId(), orderId);
        assertThat(shipped.status()).isEqualTo("SHIPPED");
        assertThat(shipped.canShip()).isFalse();

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .anySatisfy(h -> {
                    assertThat(h.getToStatus()).isEqualTo(OrderStatus.SHIPPED);
                    assertThat(h.getNote()).isEqualTo("Shipped by vendor");
                });

        // Second click: the transition is spent.
        assertThatThrownBy(() -> vendorOrderService.markShipped(vendor.getId(), orderId))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    private Page<VendorOrderResponse> pageFor(User vendor) {
        return vendorOrderService.list(vendor.getId(), PageRequest.of(0, 20));
    }

    private void setShippingAddress(Long orderId) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            var order = orderRepository.findById(orderId).orElseThrow();
            order.setRecipientName("Sipho Dlamini");
            order.setPhone("+27 83 111 2222");
            order.setAddressLine1("7 Protea Road");
            order.setCity("Stellenbosch");
            order.setProvince("Western Cape");
            order.setPostalCode("7600");
        });
    }
}
