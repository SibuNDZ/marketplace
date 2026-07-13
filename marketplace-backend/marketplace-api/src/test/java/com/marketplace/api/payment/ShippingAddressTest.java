package com.marketplace.api.payment;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12 shipping address: attachment at pay-time, and the owner-vs-admin
 * masking split in OrderService.shippingFor.
 *
 * createCheckoutSession itself is NOT exercised here — Session.create() is
 * a real call to Stripe and this suite's app.stripe.secret-key is a
 * placeholder (test/resources/application.yml), same reasoning as every
 * other "don't hit a real external service in tests" boundary in this
 * codebase. StripeCheckoutService.attachShipping is the extracted,
 * Stripe-free piece that carries the actual guarantee under test: the
 * address is committed before a session could ever be created for the
 * order.
 */
@Testcontainers
@SpringBootTest
class ShippingAddressTest {

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

    @Autowired StripeCheckoutService checkoutService;
    @Autowired PaymentEventService   paymentEventService;
    @Autowired OrderService          orderService;
    @Autowired OrderRepository       orderRepository;
    @Autowired TestFixtures          fixtures;

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static ShippingAddressRequest sampleAddress() {
        return new ShippingAddressRequest(
                "Thandiwe Mokoena", "+27 82 555 0101",
                "12 Long Street", "Unit 4",
                "Cape Town", "Western Cape", "8001");
    }

    /**
     * Covers the intent of "pay_withoutShippingFields_400": the 400 itself
     * is produced by Spring's existing MethodArgumentNotValidException ->
     * 400 mapping (already covered generically elsewhere), so what this
     * slice actually adds — and what needs verifying — is that
     * ShippingAddressRequest's fields carry the right constraints to
     * trigger that mapping in the first place.
     */
    @Test
    void shippingAddressRequest_blankFields_failValidation() {
        ShippingAddressRequest blank = new ShippingAddressRequest("", "", "", null, "", "", "");
        Set<ConstraintViolation<ShippingAddressRequest>> violations = VALIDATOR.validate(blank);

        Set<String> invalidProperties = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(invalidProperties).contains(
                "recipientName", "phone", "addressLine1", "city", "province", "postalCode");
        // addressLine2 is genuinely optional — blank must NOT be a violation.
        assertThat(invalidProperties).doesNotContain("addressLine2");
    }

    @Test
    void attachShipping_savesAddressOntoOrder() {
        Product product = fixtures.product("Shipping Test A", "SKU-SHIP-A1", new BigDecimal("25.00"), 5);
        User customer = fixtures.customerWithCart("ship-buyer1", product, 1);
        Long orderId = orderService.placeOrder(customer.getId()).id();

        checkoutService.attachShipping(orderId, customer.getId(), sampleAddress());

        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertThat(saved.getRecipientName()).isEqualTo("Thandiwe Mokoena");
        assertThat(saved.getAddressLine1()).isEqualTo("12 Long Street");
        assertThat(saved.getAddressLine2()).isEqualTo("Unit 4");
        assertThat(saved.getCity()).isEqualTo("Cape Town");
        assertThat(saved.getPostalCode()).isEqualTo("8001");
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING); // unaffected
    }

    /**
     * Named for what actually happens, not the spec draft's "_403": ownership
     * is enforced via findByIdAndUserId, so a stranger's order lookup misses
     * entirely and throws OrderNotFoundException (-> 404), not a 403. That's
     * deliberate existing behavior, not something this slice changes — 404
     * avoids confirming to a non-owner that the order even exists.
     */
    @Test
    void attachShipping_strangerCustomer_orderNotFound() {
        Product product = fixtures.product("Shipping Test B", "SKU-SHIP-B1", new BigDecimal("25.00"), 5);
        User owner = fixtures.customerWithCart("ship-owner1", product, 1);
        User stranger = fixtures.customer("ship-stranger1");
        Long orderId = orderService.placeOrder(owner.getId()).id();

        assertThatThrownBy(() ->
                checkoutService.attachShipping(orderId, stranger.getId(), sampleAddress()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void customerView_seesOwnAddress_evenWhilePending() {
        Product product = fixtures.product("Shipping Test C", "SKU-SHIP-C1", new BigDecimal("25.00"), 5);
        User customer = fixtures.customerWithCart("ship-buyer2", product, 1);
        Long orderId = orderService.placeOrder(customer.getId()).id();
        checkoutService.attachShipping(orderId, customer.getId(), sampleAddress());

        OrderResponse response = orderService.getOrder(orderId, customer.getId());

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.shippingAddress()).isNotNull();
        assertThat(response.shippingAddress().addressLine1()).isEqualTo("12 Long Street");
    }

    @Test
    void adminView_pendingOrder_addressMasked() {
        Product product = fixtures.product("Shipping Test D", "SKU-SHIP-D1", new BigDecimal("25.00"), 5);
        User customer = fixtures.customerWithCart("ship-buyer3", product, 1);
        Long orderId = orderService.placeOrder(customer.getId()).id();
        checkoutService.attachShipping(orderId, customer.getId(), sampleAddress());

        OrderResponse adminView = orderService.getOrderForAdmin(orderId);

        assertThat(adminView.shippingAddress())
                .as("address exists in the row but must be masked for admin while PENDING")
                .isNull();
    }

    @Test
    void adminView_paidOrder_addressVisible() {
        Product product = fixtures.product("Shipping Test E", "SKU-SHIP-E1", new BigDecimal("25.00"), 5);
        User customer = fixtures.customerWithCart("ship-buyer4", product, 1);
        Long orderId = orderService.placeOrder(customer.getId()).id();
        checkoutService.attachShipping(orderId, customer.getId(), sampleAddress());

        paymentEventService.handleCheckoutCompleted(orderId);

        OrderResponse adminView = orderService.getOrderForAdmin(orderId);
        assertThat(adminView.status()).isEqualTo("PAID");
        assertThat(adminView.shippingAddress()).isNotNull();
        assertThat(adminView.shippingAddress().city()).isEqualTo("Cape Town");
    }

    @Test
    void adminView_cancelledOrder_addressStillMasked() {
        Product product = fixtures.product("Shipping Test F", "SKU-SHIP-F1", new BigDecimal("25.00"), 5);
        User customer = fixtures.customerWithCart("ship-buyer5", product, 1);
        Long orderId = orderService.placeOrder(customer.getId()).id();
        checkoutService.attachShipping(orderId, customer.getId(), sampleAddress());

        orderService.cancelOrder(orderId, customer.getId());

        OrderResponse adminView = orderService.getOrderForAdmin(orderId);
        assertThat(adminView.status()).isEqualTo("CANCELLED");
        assertThat(adminView.shippingAddress())
                .as("cancellation isn't a committed purchase — address stays masked")
                .isNull();
    }
}
