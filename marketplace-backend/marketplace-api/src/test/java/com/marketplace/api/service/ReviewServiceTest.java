package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.dto.ReviewDtos.CreateReviewRequest;
import com.marketplace.api.dto.ReviewDtos.ReviewResponse;
import com.marketplace.api.dto.ReviewDtos.ReviewSummary;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.ReviewExceptions.DuplicateReviewException;
import com.marketplace.api.exception.ReviewExceptions.NotVerifiedPurchaserException;
import com.marketplace.api.exception.ReviewExceptions.ReviewNotFoundException;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Purchase-verified review service tests.
 *
 * Uses TestContainers (real PostgreSQL) so Flyway runs V1–V4 on every run —
 * each test run is also a migration test. V3 (audit NOT NULL) and V4 (unique
 * constraint) must both pass Flyway validation before any test executes.
 *
 * Key case: pendingOrder_cannotReview — proves that purchase alone is
 * insufficient; delivery is the gate.
 */
@Testcontainers
@SpringBootTest
class ReviewServiceTest {

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

    @Autowired ReviewService       reviewService;
    @Autowired ReviewRepository    reviewRepository;
    @Autowired OrderService        orderService;
    @Autowired OrderAdminService   orderAdminService;
    @Autowired PaymentEventService paymentEventService;
    @Autowired TestFixtures        fixtures;

    // -- helpers ----------------------------------------------------------

    /** Drive a freshly placed order all the way to DELIVERED via the full payment path. */
    private Long driveToDelivered(Long orderId, Long adminId) {
        paymentEventService.handleCheckoutCompleted(orderId); // PENDING -> PAID
        orderAdminService.transition(orderId, OrderStatus.SHIPPED,   adminId, "shipped");
        orderAdminService.transition(orderId, OrderStatus.DELIVERED, adminId, "delivered");
        return orderId;
    }

    private static CreateReviewRequest review(int rating, String comment) {
        return new CreateReviewRequest(rating, comment);
    }

    // -- tests ------------------------------------------------------------

    @Test
    void fullLifecycle_deliveredBuyerCanReview() {
        Product product = fixtures.product("Gadget rv1", "SKU-RV-1", new BigDecimal("29.99"), 5);
        User buyer = fixtures.customerWithCart("rv-buyer1", product, 1);
        User admin = fixtures.admin("rv-admin");

        OrderResponse order = orderService.placeOrder(buyer.getId());
        driveToDelivered(order.id(), admin.getId());

        ReviewResponse response = reviewService.create(product.getId(),
                review(5, "Excellent!"), buyer.getId());

        assertThat(response.id()).isPositive();
        assertThat(response.productId()).isEqualTo(product.getId());
        assertThat(response.reviewerId()).isEqualTo(buyer.getId());
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Excellent!");
        assertThat(response.createdAt()).isNotNull();

        ReviewSummary summary = reviewService.summary(product.getId());
        assertThat(summary.reviewCount()).isEqualTo(1);
        assertThat(summary.averageRating()).isEqualTo(5.0);

        Page<ReviewResponse> page = reviewService.listForProduct(
                product.getId(), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    /**
     * THE core rule: purchase alone is insufficient — delivery is the gate.
     * A PENDING order does not qualify.
     */
    @Test
    void pendingOrder_cannotReview() {
        Product product = fixtures.product("Gadget rv2", "SKU-RV-2", new BigDecimal("29.99"), 5);
        User buyer = fixtures.customerWithCart("rv-buyer2", product, 1);

        orderService.placeOrder(buyer.getId()); // order stays PENDING

        assertThatThrownBy(() ->
                reviewService.create(product.getId(), review(4, "Haven't received it"), buyer.getId()))
                .isInstanceOf(NotVerifiedPurchaserException.class);
    }

    @Test
    void cancelledOrder_cannotReview() {
        Product product = fixtures.product("Gadget rv3", "SKU-RV-3", new BigDecimal("29.99"), 5);
        User buyer = fixtures.customerWithCart("rv-buyer3", product, 1);

        OrderResponse order = orderService.placeOrder(buyer.getId());
        orderService.cancelOrder(order.id(), buyer.getId());

        assertThatThrownBy(() ->
                reviewService.create(product.getId(), review(1, "Cancelled"), buyer.getId()))
                .isInstanceOf(NotVerifiedPurchaserException.class);
    }

    @Test
    void duplicate_rejected() {
        Product product = fixtures.product("Gadget rv4", "SKU-RV-4", new BigDecimal("29.99"), 5);
        User buyer = fixtures.customerWithCart("rv-buyer4", product, 1);
        User admin = fixtures.admin("rv-admin");

        OrderResponse order = orderService.placeOrder(buyer.getId());
        driveToDelivered(order.id(), admin.getId());

        reviewService.create(product.getId(), review(4, "Good"), buyer.getId());

        assertThatThrownBy(() ->
                reviewService.create(product.getId(), review(3, "Second attempt"), buyer.getId()))
                .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    void nonPurchaser_cannotReview() {
        Product product = fixtures.product("Gadget rv5", "SKU-RV-5", new BigDecimal("29.99"), 5);
        User buyer    = fixtures.customerWithCart("rv-buyer5", product, 1);
        User stranger = fixtures.customer("rv-stranger5");
        User admin    = fixtures.admin("rv-admin");

        OrderResponse order = orderService.placeOrder(buyer.getId());
        driveToDelivered(order.id(), admin.getId());

        // stranger never purchased the product
        assertThatThrownBy(() ->
                reviewService.create(product.getId(), review(2, "Reviewing without buying"),
                        stranger.getId()))
                .isInstanceOf(NotVerifiedPurchaserException.class);
    }

    @Test
    void authorCanUpdate_strangerCannotUpdateOrDelete() {
        Product product = fixtures.product("Gadget rv6", "SKU-RV-6", new BigDecimal("29.99"), 5);
        User buyer    = fixtures.customerWithCart("rv-buyer6", product, 1);
        User stranger = fixtures.customer("rv-stranger6");
        User admin    = fixtures.admin("rv-admin");

        OrderResponse order = orderService.placeOrder(buyer.getId());
        driveToDelivered(order.id(), admin.getId());

        ReviewResponse created = reviewService.create(product.getId(),
                review(4, "Original"), buyer.getId());
        Long reviewId = created.id();

        // author can update their own review
        ReviewResponse updated = reviewService.update(reviewId,
                review(5, "Updated"), buyer.getId());
        assertThat(updated.rating()).isEqualTo(5);
        assertThat(updated.comment()).isEqualTo("Updated");

        // stranger update → 404 (not 403: information-hiding)
        assertThatThrownBy(() ->
                reviewService.update(reviewId, review(1, "Sabotage"), stranger.getId()))
                .isInstanceOf(ReviewNotFoundException.class);

        // stranger delete → 404
        assertThatThrownBy(() ->
                reviewService.delete(reviewId, stranger.getId(), false))
                .isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void adminCanDeleteAnyReview() {
        Product product = fixtures.product("Gadget rv7", "SKU-RV-7", new BigDecimal("29.99"), 5);
        User buyer = fixtures.customerWithCart("rv-buyer7", product, 1);
        User admin = fixtures.admin("rv-admin");

        OrderResponse order = orderService.placeOrder(buyer.getId());
        driveToDelivered(order.id(), admin.getId());

        ReviewResponse created = reviewService.create(product.getId(),
                review(5, "Great"), buyer.getId());
        Long reviewId = created.id();

        // admin deletes a review they didn't write
        reviewService.delete(reviewId, admin.getId(), true);

        assertThat(reviewRepository.findById(reviewId)).isEmpty();

        ReviewSummary summary = reviewService.summary(product.getId());
        assertThat(summary.reviewCount()).isEqualTo(0);
    }
}
