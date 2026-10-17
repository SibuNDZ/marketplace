package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.UserRepository;
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
 * Exercises the real PaystackCheckoutService against a local stub standing in
 * for api.paystack.co, so the request actually put on the wire is asserted:
 * email, cents as a string, ZAR, reference, callback and cancel_action.
 */
@Testcontainers
@SpringBootTest
class PaystackCheckoutServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer STUB;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    static final AtomicInteger REQUEST_COUNT = new AtomicInteger();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/transaction/initialize", exchange -> {
                REQUEST_COUNT.incrementAndGet();
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
        registry.add("app.paystack.initialize-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/transaction/initialize");
        registry.add("app.paystack.secret-key", () -> "sk_test_stub_key");
    }

    @Autowired PaystackCheckoutService paystackCheckoutService;
    @Autowired OrderService            orderService;
    @Autowired OrderRepository         orderRepository;
    @Autowired UserRepository          userRepository;
    @Autowired TestFixtures            fixtures;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ShippingAddressRequest ADDRESS = new ShippingAddressRequest(
            "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
            null, "Gqeberha", "Eastern Cape", "6001");

    @BeforeEach
    void resetStub() {
        STATUS.set(200);
        RESPONSE_BODY.set("""
                {"status":true,"message":"Authorization URL created",
                 "data":{"authorization_url":"https://checkout.paystack.com/stub123",
                         "access_code":"stub123","reference":"ignored"}}""");
        REQUEST_COUNT.set(0);
        LAST_REQUEST_BODY.set(null);
        LAST_AUTHORIZATION.set(null);
    }

    private record Placed(Long orderId, Long userId) {}

    private Placed placedOrder(String tag, String price) {
        Product product = fixtures.product("PSC-" + tag, "SKU-PSC-" + tag, new BigDecimal(price), 5);
        User buyer = fixtures.customerWithCart("psc-buyer-" + tag, product, 1);
        return new Placed(orderService.placeOrder(buyer.getId()).id(), buyer.getId());
    }

    @Test
    void initializes_withCentsZarEmailReferenceAndUrls() throws Exception {
        Placed placed = placedOrder("REQ1", "250.00");

        String url = paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);
        assertThat(url).isEqualTo("https://checkout.paystack.com/stub123");

        JsonNode sent = objectMapper.readTree(LAST_REQUEST_BODY.get());
        Order order = orderRepository.findById(placed.orderId()).orElseThrow();

        long expectedCents = order.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValueExact();
        assertThat(sent.get("amount").asText()).isEqualTo(String.valueOf(expectedCents));
        assertThat(sent.get("currency").asText()).isEqualTo("ZAR");
        assertThat(sent.get("email").asText()).isEqualTo(userRepository.findById(placed.userId()).orElseThrow().getEmail());
        assertThat(sent.get("reference").asText()).startsWith("ERY-" + placed.orderId() + "-");
        assertThat(sent.get("callback_url").asText()).endsWith("/checkout/success?order=" + placed.orderId());

        JsonNode metadata = sent.path("metadata");
        assertThat(metadata.path("orderId").asText()).isEqualTo(String.valueOf(placed.orderId()));
        assertThat(metadata.path("orderNumber").asText()).isEqualTo(order.getOrderNumber());
        assertThat(metadata.path("cancel_action").asText())
                .endsWith("/checkout/cancelled?order=" + placed.orderId());

        assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer sk_test_stub_key");
    }

    @Test
    void secondAttemptOnSameOrder_usesFreshReference() throws Exception {
        // Paystack rejects a reused reference, and paying a PENDING order a
        // second time is legitimate.
        Placed placed = placedOrder("REF1", "45.00");

        paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);
        String first = objectMapper.readTree(LAST_REQUEST_BODY.get()).get("reference").asText();
        paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);
        String second = objectMapper.readTree(LAST_REQUEST_BODY.get()).get("reference").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(second).matches("[A-Za-z0-9.=-]+"); // Paystack's allowed reference charset
    }

    @Test
    void addressIsPersistedInTheSameTransaction() {
        Placed placed = placedOrder("ADDR1", "180.00");
        paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS);

        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(order.getAddressLine1()).isEqualTo("12 Milkwood Lane");
    }

    @Test
    void badKey_isMisconfigured_andPersistsNothing() {
        Placed placed = placedOrder("ERR1", "310.00");
        STATUS.set(401);
        RESPONSE_BODY.set("{\"status\":false,\"message\":\"Invalid key\"}");

        assertThatThrownBy(() ->
                paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS))
                .isInstanceOf(PaymentExceptions.PaymentProviderMisconfiguredException.class);

        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getAddressLine1()).isNull();
    }

    @Test
    void ambiguousServerError_isNotRetried() {
        Placed placed = placedOrder("NORETRY1", "60.00");
        STATUS.set(502);
        RESPONSE_BODY.set("{\"status\":false,\"message\":\"bad gateway\"}");

        assertThatThrownBy(() ->
                paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS))
                .isInstanceOf(PaymentExceptions.PaymentProviderUnavailableException.class);
        assertThat(REQUEST_COUNT.get()).isEqualTo(1);
    }

    @Test
    void responseWithoutAuthorizationUrl_isAnError() {
        Placed placed = placedOrder("NOURL1", "60.00");
        RESPONSE_BODY.set("{\"status\":true,\"data\":{}}");

        assertThatThrownBy(() ->
                paystackCheckoutService.createCheckout(placed.orderId(), placed.userId(), ADDRESS))
                .isInstanceOf(PaymentExceptions.PaymentProviderException.class)
                .hasMessageContaining("authorization_url");
    }

    @Test
    void payingSomeoneElsesOrder_isNotFound() {
        Placed mine = placedOrder("OWN1", "50.00");
        Placed theirs = placedOrder("OWN2", "50.00");

        assertThatThrownBy(() ->
                paystackCheckoutService.createCheckout(mine.orderId(), theirs.userId(), ADDRESS))
                .isInstanceOf(com.marketplace.api.exception.OrderExceptions.OrderNotFoundException.class);
        assertThat(REQUEST_COUNT.get()).isZero();
    }
}
