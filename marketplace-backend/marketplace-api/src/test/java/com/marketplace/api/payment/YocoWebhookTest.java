package com.marketplace.api.payment;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Yoco webhook gauntlet, end to end through the unauthenticated endpoint
 * (the permitAll carve-out is part of what is under test). Signatures are
 * computed with a local HMAC helper written from Yoco's doc, never by calling
 * YocoSignature — same anti-circularity rule as YocoSignatureTest.
 *
 * Timestamps here are Instant.now() because the real controller uses the real
 * clock; the frozen-clock cases live in YocoSignatureTest.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class YocoWebhookTest {

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

    /** Matches test/resources/application.yml. */
    static final String SECRET = "whsec_eW9jby10ZXN0LXdlYmhvb2stc2VjcmV0LTMyYnl0ZXM=";

    @Autowired MockMvc                      mockMvc;
    @Autowired OrderService                 orderService;
    @Autowired OrderRepository              orderRepository;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired TestFixtures                 fixtures;

    // --- helpers -----------------------------------------------------------

    private Long placedOrder(String tag, String price) {
        Product product = fixtures.product("YC-" + tag, "SKU-YC-" + tag, new BigDecimal(price), 5);
        User buyer = fixtures.customerWithCart("yc-buyer-" + tag, product, 1);
        return orderService.placeOrder(buyer.getId()).id();
    }

    private static String successBody(Long orderId) {
        return "{\"id\":\"evt_" + orderId + "\",\"type\":\"payment.succeeded\","
                + "\"createdDate\":\"2026-08-03T09:00:00.000Z\",\"payload\":{"
                + "\"id\":\"p_" + orderId + "\",\"type\":\"payment\",\"status\":\"succeeded\","
                + "\"amount\":15000,\"currency\":\"ZAR\",\"mode\":\"test\","
                + "\"metadata\":{\"orderId\":\"" + orderId + "\"}}}";
    }

    private void postWebhook(String body, String id, String timestamp,
                             String signature, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/payments/yoco/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("webhook-id", id)
                        .header("webhook-timestamp", timestamp)
                        .header("webhook-signature", signature)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    /** Signed and delivered now, the way a real delivery arrives. */
    private void postSigned(String body, int expectedStatus) throws Exception {
        String id = "msg_" + Math.abs(body.hashCode());
        String ts = String.valueOf(Instant.now().getEpochSecond());
        postWebhook(body, id, ts, sign(id, ts, body), expectedStatus);
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    // --- the gauntlet ------------------------------------------------------

    @Test
    void paymentSucceeded_flipsPendingToPaid_withHistory() throws Exception {
        Long orderId = placedOrder("OK1", "150.00");
        postSigned(successBody(orderId), 200);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .anySatisfy(h -> assertThat(h.getToStatus()).isEqualTo(OrderStatus.PAID));
    }

    @Test
    void duplicateDelivery_isIdempotent() throws Exception {
        Long orderId = placedOrder("DUP1", "120.00");
        String body = successBody(orderId);
        postSigned(body, 200);
        postSigned(body, 200);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .filteredOn(h -> h.getToStatus() == OrderStatus.PAID)
                .hasSize(1);
    }

    @Test
    void auditNoteNamesYoco_notStripe() throws Exception {
        // The shared transition used to hardcode "(Stripe)" for every
        // provider. With three providers that misattribution is a
        // reconciliation trap, so the label is now passed in.
        Long orderId = placedOrder("LBL1", "90.00");
        postSigned(successBody(orderId), 200);

        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .filteredOn(h -> h.getToStatus() == OrderStatus.PAID)
                .singleElement()
                .satisfies(h -> assertThat(h.getNote()).isEqualTo("Payment completed (Yoco)"));
    }

    @Test
    void tamperedSignature_400_orderUntouched() throws Exception {
        Long orderId = placedOrder("SIG1", "80.00");
        String body = successBody(orderId);
        String id = "msg_sig1";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String corrupt = sign(id, ts, body);
        corrupt = corrupt.substring(0, corrupt.length() - 5) + "AAAA=";

        postWebhook(body, id, ts, corrupt, 400);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void tamperedBody_400_orderUntouched() throws Exception {
        // Signature computed over the ORIGINAL body, then the amount edited:
        // the replay-with-edit attack the HMAC exists to stop.
        Long orderId = placedOrder("TMP1", "80.00");
        String body = successBody(orderId);
        String id = "msg_tmp1";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(id, ts, body);

        postWebhook(body.replace("15000", "1"), id, ts, signature, 400);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void staleTimestamp_400_orderUntouched() throws Exception {
        Long orderId = placedOrder("OLD1", "70.00");
        String body = successBody(orderId);
        String id = "msg_old1";
        // Correctly signed, but signed ten minutes ago: a captured delivery
        // being replayed. Tolerance is 3 minutes.
        String ts = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());

        postWebhook(body, id, ts, sign(id, ts, body), 400);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void missingSignatureHeaders_400() throws Exception {
        Long orderId = placedOrder("HDR1", "55.00");
        mockMvc.perform(post("/api/v1/payments/yoco/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody(orderId)))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void unknownEventType_200_orderUntouched() throws Exception {
        Long orderId = placedOrder("UNK1", "65.00");
        String body = "{\"id\":\"evt_x\",\"type\":\"refund.succeeded\",\"payload\":{"
                + "\"id\":\"r_1\",\"metadata\":{\"orderId\":\"" + orderId + "\"}}}";

        // Unknown is not an error: acknowledge so the delivery ladder stops.
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void paymentFailed_200_orderStaysPending() throws Exception {
        Long orderId = placedOrder("FAIL1", "65.00");
        String body = successBody(orderId).replace("payment.succeeded", "payment.failed");

        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void successWithoutMetadata_200_orderUntouched() throws Exception {
        // Yoco's payment webhook carries NO checkout reference, so a delivery
        // without our metadata is unreconcilable. It must still be
        // acknowledged (retrying cannot add metadata) and logged as an error.
        Long orderId = placedOrder("MET1", "75.00");
        String body = "{\"id\":\"evt_nometa\",\"type\":\"payment.succeeded\",\"payload\":{"
                + "\"id\":\"p_nometa\",\"status\":\"succeeded\",\"amount\":7500,"
                + "\"currency\":\"ZAR\",\"metadata\":{}}}";

        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void metadataForUnknownOrder_200_noCrash() throws Exception {
        String body = successBody(999_999_999L);
        postSigned(body, 200);
    }

    /** Local HMAC, written from Yoco's doc — not YocoSignature's code. */
    private static String sign(String id, String timestamp, String body) {
        try {
            byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
