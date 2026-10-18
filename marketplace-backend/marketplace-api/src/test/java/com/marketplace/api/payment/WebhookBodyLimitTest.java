package com.marketplace.api.payment;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.marketplace.api.web.WebhookBodyLimitFilter.MAX_BODY_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment callback body cap through a real Tomcat. MockMvc would skip
 * the two things under test: it always reports a Content-Length, so the
 * chunked path never runs, and it bypasses the container's own form parsing,
 * which is what the PayFast replay has to stand in for.
 *
 * Deliveries are signed with the helpers the provider suites already trust
 * (YocoWebhookTest.sign, PayfastItnTest.itnBody), so "still pays" means the
 * signature survived the filter, not just that a 200 came back.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PayfastItnTest.StubValidatorConfig.class)
class WebhookBodyLimitTest {

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

    @LocalServerPort int             port;
    @Autowired       OrderService    orderService;
    @Autowired       OrderRepository orderRepository;
    @Autowired       TestFixtures    fixtures;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // --- Content-Length declared -------------------------------------------

    @Test
    void signedWebhookOverTheCap_413_beforeTheControllerRuns() throws Exception {
        // Correctly signed, so the controller WOULD pay this order. Still
        // PENDING afterwards means the filter answered, not the signature check.
        Long orderId = placedOrder("BIG1");
        String body = paddedTo(YocoWebhookTest.successBody(orderId), MAX_BODY_BYTES + 1);

        assertThat(postYoco(body, false)).isEqualTo(413);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void signedWebhookAtTheCap_stillPays() throws Exception {
        Long orderId = placedOrder("CAP1");
        String body = paddedTo(YocoWebhookTest.successBody(orderId), MAX_BODY_BYTES);

        assertThat(postYoco(body, false)).isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/payments/stripe/webhook",
            "/api/v1/payments/yoco/webhook",
            "/api/v1/payments/payfast/itn"})
    void everyProviderCallback_isCapped(String path) throws Exception {
        // Unsigned junk. Without the filter Stripe and Yoco answer 400 and
        // PayFast 415, so a 413 can only have come from the cap.
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(publisher("x".repeat(MAX_BODY_BYTES + 1), false))
                .build();

        assertThat(http.send(request, BodyHandlers.discarding()).statusCode()).isEqualTo(413);
    }

    // --- chunked, no Content-Length ----------------------------------------

    @Test
    void chunkedWebhookOverTheCap_isCutOff_413() throws Exception {
        Long orderId = placedOrder("CHK1");
        String body = paddedTo(YocoWebhookTest.successBody(orderId), MAX_BODY_BYTES + 8 * 1024);

        assertThat(postYoco(body, true)).isEqualTo(413);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void chunkedSignedWebhook_stillPays() throws Exception {
        Long orderId = placedOrder("CHK2");

        assertThat(postYoco(YocoWebhookTest.successBody(orderId), true)).isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void chunkedPayfastItn_stillPays() throws Exception {
        // Spring rebuilds a form body from getParameterMap(), so this only
        // verifies if the filter's replay kept every field, its encoding,
        // and the wire order PayFast signed.
        Long orderId = placedOrder("ITN1");
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/payments/payfast/itn"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(publisher(PayfastItnTest.itnBody(orderId, "150.00", "COMPLETE"), true))
                .build();

        assertThat(http.send(request, BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    // --- helpers -----------------------------------------------------------

    private Long placedOrder(String tag) {
        Product product = fixtures.product("BL-" + tag, "SKU-BL-" + tag, new BigDecimal("150.00"), 5);
        User buyer = fixtures.customerWithCart("bl-buyer-" + tag, product, 1);
        return orderService.placeOrder(buyer.getId()).id();
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Signed and delivered now, the way a real Yoco delivery arrives. */
    private int postYoco(String body, boolean chunked) throws Exception {
        String id = "msg_" + Math.abs(body.hashCode());
        String ts = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/payments/yoco/webhook"))
                .header("Content-Type", "application/json")
                .header("webhook-id", id)
                .header("webhook-timestamp", ts)
                .header("webhook-signature", YocoWebhookTest.sign(id, ts, body))
                .POST(publisher(body, chunked))
                .build();
        return http.send(request, BodyHandlers.discarding()).statusCode();
    }

    /**
     * ofByteArray declares a Content-Length. ofInputStream cannot know one,
     * so the JDK client frames the body as Transfer-Encoding: chunked, the
     * route a sender would take to get past a header check.
     */
    private static BodyPublisher publisher(String body, boolean chunked) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return chunked
                ? BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : BodyPublishers.ofByteArray(bytes);
    }

    /** Trailing whitespace is valid JSON and is covered by the signature like any other byte. */
    private static String paddedTo(String json, int size) {
        return json + " ".repeat(size - json.getBytes(StandardCharsets.UTF_8).length);
    }
}
