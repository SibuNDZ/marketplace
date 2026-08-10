package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real YocoCheckoutService against a local stub standing in for
 * payments.yoco.com, so the request we actually put on the wire is asserted
 * rather than assumed: cents, ZAR, metadata, and the Idempotency-Key header.
 *
 * The Stripe equivalent (ShippingAddressTest) cannot do this — it documents
 * that createCheckoutSession is untested because it would hit Stripe for real.
 * Yoco's plain-HTTP seam is redirectable at a stub, so the checkout side gets
 * genuine coverage here.
 */
@Testcontainers
@SpringBootTest
class YocoCheckoutServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer STUB;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_IDEMPOTENCY_KEY = new AtomicReference<>();
    static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    static final AtomicInteger REQUEST_COUNT = new AtomicInteger();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/api/checkouts", exchange -> {
                REQUEST_COUNT.incrementAndGet();
                LAST_IDEMPOTENCY_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                LAST_REQUEST_BODY.set(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] out = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(STATUS.get(), out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            STUB.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.yoco.checkouts-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/api/checkouts");
        registry.add("app.yoco.secret-key", () -> "sk_test_stub_key");
    }

    @Autowired YocoCheckoutService yocoCheckoutService;
    @Autowired OrderService        orderService;
    @Autowired OrderRepository     orderRepository;
    @Autowired TestFixtures        fixtures;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ShippingAddressRequest ADDRESS = new ShippingAddressRequest(
            "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
            null, "Gqeberha", "Eastern Cape", "6001");

    @BeforeEach
    void resetStub() {
        STATUS.set(200);
        RESPONSE_BODY.set("""
                {"id":"ch_stub_1","status":"created",
                 "redirectUrl":"https://c.yoco.com/checkout/ch_stub_1",
                 "amount":25000,"currency":"ZAR","processingMode":"test"}""");
        REQUEST_COUNT.set(0);
        LAST_REQUEST_BODY.set(null);
        LAST_IDEMPOTENCY_KEY.set(null);
        LAST_AUTHORIZATION.set(null);
    }

    private record Placed(Long orderId, Long userId) {}

    private Placed placedOrder(String tag, String price) {
        Product product = fixtures.product("YCS-" + tag, "SKU-YCS-" + tag, new BigDecimal(price), 5);
        User buyer = fixtures.customerWithCart("ycs-buyer-" + tag, product, 1);
        return new Placed(orderService.placeOrder(buyer.getId()).id(), buyer.getId());
    }

    @Test
    void createsCheckout_sendsCentsZarAndOrderMetadata() throws Exception {
        Placed placed = placedOrder("REQ1", "250.00");

        String url = yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);
        assertThat(url).isEqualTo("https://c.yoco.com/checkout/ch_stub_1");

        JsonNode sent = objectMapper.readTree(LAST_REQUEST_BODY.get());
        Order order = orderRepository.findById(placed.orderId()).orElseThrow();

        // Amount must be the order total in cents — the single source of truth
        // the webhook will later mark PAID.
        long expectedCents = order.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValueExact();
        assertThat(sent.get("amount").asLong()).isEqualTo(expectedCents);
        assertThat(sent.get("currency").asText()).isEqualTo("ZAR");

        // Metadata is the ONLY link back from the webhook: no checkout id is
        // echoed in payment.succeeded.
        assertThat(sent.path("metadata").path("orderId").asText())
                .isEqualTo(String.valueOf(placed.orderId()));
        assertThat(sent.path("metadata").path("orderNumber").asText())
                .isEqualTo(order.getOrderNumber());

        assertThat(sent.get("successUrl").asText()).endsWith("?order=" + placed.orderId());
        assertThat(sent.get("cancelUrl").asText()).endsWith("?order=" + placed.orderId());
        assertThat(sent.get("failureUrl").asText()).endsWith("?order=" + placed.orderId());
    }

    @Test
    void sendsBearerAuthAndIdempotencyKey() {
        Placed placed = placedOrder("HDR1", "99.00");
        yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);

        assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer sk_test_stub_key");
        assertThat(LAST_IDEMPOTENCY_KEY.get()).isNotBlank();
    }

    @Test
    void secondAttemptOnSameOrder_usesFreshIdempotencyKey() {
        // Idempotency is scoped to one payment ATTEMPT, not to the order: a
        // customer who abandons the first Yoco page must get a live checkout
        // on the second try, not a replay of a possibly-expired one.
        Placed placed = placedOrder("IDEM1", "45.00");

        yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);
        String first = LAST_IDEMPOTENCY_KEY.get();
        yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);
        String second = LAST_IDEMPOTENCY_KEY.get();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void addressIsPersistedInTheSameTransaction() {
        Placed placed = placedOrder("ADDR1", "180.00");
        yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);

        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(order.getAddressLine1()).isEqualTo("12 Milkwood Lane");
        assertThat(order.getCity()).isEqualTo("Gqeberha");
    }

    @Test
    void providerError_persistsNothing_orderStaysPendingWithNoAddress() {
        Placed placed = placedOrder("ERR1", "310.00");
        STATUS.set(500);
        RESPONSE_BODY.set("{\"message\":\"internal error\"}");

        assertThatThrownBy(() ->
                yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS))
                .isInstanceOf(PaymentExceptions.PaymentProviderException.class);

        // The address write shares the transaction with the provider call, so
        // a failed checkout must leave no trace at all.
        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getAddressLine1()).isNull();
    }

    @Test
    void ambiguousServerError_isNotRetried() {
        // Only connect failures are retryable. A 5xx might mean the checkout
        // WAS created, so retrying it is exactly the double-charge path the
        // retry discipline exists to avoid.
        Placed placed = placedOrder("NORETRY1", "60.00");
        STATUS.set(502);
        RESPONSE_BODY.set("{\"message\":\"bad gateway\"}");

        assertThatThrownBy(() ->
                yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS))
                .isInstanceOf(PaymentExceptions.PaymentProviderException.class);

        assertThat(REQUEST_COUNT.get()).isEqualTo(1);
    }

    @Test
    void responseWithoutRedirectUrl_isAnError() {
        Placed placed = placedOrder("NOURL1", "60.00");
        RESPONSE_BODY.set("{\"id\":\"ch_stub_2\",\"status\":\"created\"}");

        assertThatThrownBy(() ->
                yocoCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS))
                .isInstanceOf(PaymentExceptions.PaymentProviderException.class)
                .hasMessageContaining("redirectUrl");
    }

    @Test
    void payingSomeoneElsesOrder_isNotFound() {
        Placed mine = placedOrder("OWN1", "50.00");
        Placed theirs = placedOrder("OWN2", "50.00");

        // CheckoutPreparation enforces this for every provider; pinned here so
        // the Yoco path cannot quietly skip the shared seam.
        assertThatThrownBy(() ->
                yocoCheckoutService.createCheckout(mine.orderId(), theirs.userId(), ADDRESS))
                .isInstanceOf(com.marketplace.api.exception.OrderExceptions.OrderNotFoundException.class);

        assertThat(REQUEST_COUNT.get()).isZero();
    }
}
