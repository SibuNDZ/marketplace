package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery fee behavior at checkout (Task 2.3):
 * one flat fee per UNIQUE vendor in the cart, snapshotted at placement so
 * later vendor edits never reprice an existing order, included in
 * totalAmount, and visible to buyer (OrderResponse) and vendor
 * (VendorOrderResponse) alike.
 *
 * Not @Transactional — fixture and checkout transactions must commit
 * independently (same reasoning as the other order tests).
 */
@Testcontainers
@SpringBootTest
class DeliveryFeeTest {

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

    @Autowired OrderService          orderService;
    @Autowired VendorOrderService    vendorOrderService;
    @Autowired PaymentEventService   paymentEventService;
    @Autowired OrderRepository       orderRepository;
    @Autowired UserRepository        userRepository;
    @Autowired TestFixtures          fixtures;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void oneFeePerUniqueVendor_includedInTotal_andZeroFeeVendorAddsNoLine() {
        User feeVendor = fixtures.vendor("df-vendor1");
        setFee(feeVendor, "50.00");
        User freeVendor = fixtures.vendor("df-vendor2"); // fee stays 0

        // TWO products from the fee-charging vendor: the fee must apply ONCE.
        Product p1 = fixtures.productForVendor("DF-One", "SKU-DF-1", new BigDecimal("100.00"), 5, feeVendor);
        Product p2 = fixtures.productForVendor("DF-Two", "SKU-DF-2", new BigDecimal("200.00"), 5, feeVendor);
        Product p3 = fixtures.productForVendor("DF-Free", "SKU-DF-3", new BigDecimal("40.00"), 5, freeVendor);
        User buyer = fixtures.customerWithCartOf("df-buyer1", p1, p2, p3);

        OrderResponse order = orderService.placeOrder(buyer.getId());

        // 100 + 200 + 40 items, plus exactly one 50.00 fee.
        assertThat(order.total()).isEqualByComparingTo("390.00");
        assertThat(order.deliveryFees()).hasSize(1);
        assertThat(order.deliveryFees().get(0).vendorName()).isEqualTo("df-vendor1 test");
        assertThat(order.deliveryFees().get(0).fee()).isEqualByComparingTo("50.00");
    }

    @Test
    void multiVendorCart_getsOneFeePerVendor() {
        User vendorA = fixtures.vendor("df-vendorA");
        User vendorB = fixtures.vendor("df-vendorB");
        setFee(vendorA, "30.00");
        setFee(vendorB, "45.00");
        Product pa = fixtures.productForVendor("DF-Aloe", "SKU-DF-A", new BigDecimal("100.00"), 5, vendorA);
        Product pb = fixtures.productForVendor("DF-Buchu", "SKU-DF-B", new BigDecimal("100.00"), 5, vendorB);
        User buyer = fixtures.customerWithCartOf("df-buyer2", pa, pb);

        OrderResponse order = orderService.placeOrder(buyer.getId());

        assertThat(order.total()).isEqualByComparingTo("275.00");
        assertThat(order.deliveryFees()).hasSize(2);
        assertThat(order.deliveryFees())
                .extracting(OrderResponse.DeliveryFeeResponse::fee)
                .satisfiesExactlyInAnyOrder(
                        f -> assertThat(f).isEqualByComparingTo("30.00"),
                        f -> assertThat(f).isEqualByComparingTo("45.00"));
    }

    @Test
    void feeIsSnapshotted_vendorEditsNeverRepriceExistingOrders_andVendorViewShowsIt() {
        User vendor = fixtures.vendor("df-vendor3");
        setFee(vendor, "25.00");
        Product p = fixtures.productForVendor("DF-Snap", "SKU-DF-S", new BigDecimal("60.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf("df-buyer3", p);

        Long orderId = orderService.placeOrder(buyer.getId()).id();

        setFee(vendor, "999.00"); // the edit that must NOT touch the order

        OrderResponse order = orderService.getOrder(orderId, buyer.getId());
        assertThat(order.total()).isEqualByComparingTo("85.00");
        assertThat(order.deliveryFees().get(0).fee()).isEqualByComparingTo("25.00");

        // Vendor's own view carries the snapshot too (visible once PAID).
        paymentEventService.handleCheckoutCompleted(orderId);
        assertThat(vendorOrderService.get(vendor.getId(), orderId).deliveryFee())
                .isEqualByComparingTo("25.00");
        // Their items subtotal stays item-only; the fee is its own field.
        assertThat(vendorOrderService.get(vendor.getId(), orderId).itemsTotal())
                .isEqualByComparingTo("60.00");
    }

    private void setFee(User vendor, String fee) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                userRepository.findById(vendor.getId()).orElseThrow()
                        .setDeliveryFee(new BigDecimal(fee)));
    }
}
