package com.marketplace.api.payment;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ITN gauntlet, end to end through the unauthenticated controller (the
 * permitAll carve-out is part of what is under test). The validator seam is
 * stubbed; everything else is real, including the idempotent PENDING -> PAID
 * transition the Stripe webhook tests already trust.
 *
 * Signatures are computed with the deterministic test passphrase from
 * test/resources/application.yml, over the wire order of the body being
 * posted (deliberately NOT the documented request order, to pin the
 * received-order rule).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PayfastItnTest.StubValidatorConfig.class)
class PayfastItnTest {

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

    static final AtomicBoolean VALIDATOR_ANSWER = new AtomicBoolean(true);

    @TestConfiguration
    static class StubValidatorConfig {
        @Bean @Primary
        PayfastValidator stubValidator() {
            return paramString -> VALIDATOR_ANSWER.get();
        }
    }

    static final String PASSPHRASE = "test-passphrase"; // matches test yml

    @Autowired MockMvc                      mockMvc;
    @Autowired OrderService                 orderService;
    @Autowired OrderRepository              orderRepository;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired PayfastCheckoutService       payfastCheckoutService;
    @Autowired TestFixtures                 fixtures;

    @BeforeEach
    void resetValidator() {
        VALIDATOR_ANSWER.set(true);
    }

    // --- helpers -----------------------------------------------------------

    private Long placedOrder(String tag, String price) {
        Product product = fixtures.product("PF-" + tag, "SKU-PF-" + tag, new BigDecimal(price), 5);
        User buyer = fixtures.customerWithCart("pf-buyer-" + tag, product, 1);
        return orderService.placeOrder(buyer.getId()).id();
    }

    /** ITN body in a deliberately non-documented wire order, correctly signed. */
    private String itnBody(Long orderId, String amountGross, String paymentStatus) {
        String params = "pf_payment_id=1089250"
                + "&m_payment_id=" + orderId
                + "&payment_status=" + paymentStatus
                + "&item_name=eRestyu+order"
                + "&custom_str1="
                + "&amount_gross=" + amountGross
                + "&merchant_id=10000100";
        return params + "&signature=" + md5(params + "&passphrase=" + PASSPHRASE);
    }

    private void postItn(String body) throws Exception {
        mockMvc.perform(post("/api/v1/payments/payfast/itn")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(body))
                .andExpect(status().isOk());
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    // --- the gauntlet ------------------------------------------------------

    @Test
    void completeItn_transitionsPendingToPaid_withHistory() throws Exception {
        Long orderId = placedOrder("OK1", "150.00");
        postItn(itnBody(orderId, "150.00", "COMPLETE"));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .anySatisfy(h -> assertThat(h.getToStatus()).isEqualTo(OrderStatus.PAID));
    }

    @Test
    void badSignature_dropped_orderUntouched() throws Exception {
        Long orderId = placedOrder("SIG1", "80.00");
        String body = itnBody(orderId, "80.00", "COMPLETE");
        postItn(body.substring(0, body.length() - 4) + "beef"); // corrupt the signature

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void amountMismatch_dropped_orderUntouched() throws Exception {
        Long orderId = placedOrder("AMT1", "200.00");
        // A signed, internally consistent ITN whose amount simply is not the
        // order's total: exactly what a tampered client-side form produces.
        postItn(itnBody(orderId, "2.00", "COMPLETE"));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void amountWithinToleranceOfOneCent_accepted() throws Exception {
        Long orderId = placedOrder("TOL1", "99.99");
        postItn(itnBody(orderId, "100.00", "COMPLETE")); // +0.01: rounding, not tampering

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void validatorRefusal_dropped_orderUntouched() throws Exception {
        Long orderId = placedOrder("VAL1", "60.00");
        VALIDATOR_ANSWER.set(false);
        postItn(itnBody(orderId, "60.00", "COMPLETE"));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void duplicateCompleteItn_isIdempotent() throws Exception {
        Long orderId = placedOrder("DUP1", "120.00");
        String body = itnBody(orderId, "120.00", "COMPLETE");
        postItn(body);
        postItn(body);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .filteredOn(h -> h.getToStatus() == OrderStatus.PAID)
                .hasSize(1);
    }

    @Test
    void cancelledStatus_noTransition() throws Exception {
        Long orderId = placedOrder("CAN1", "45.00");
        postItn(itnBody(orderId, "45.00", "CANCELLED"));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void foreignMerchantId_dropped() throws Exception {
        Long orderId = placedOrder("MER1", "70.00");
        String params = "m_payment_id=" + orderId
                + "&payment_status=COMPLETE&amount_gross=70.00&merchant_id=99999999";
        postItn(params + "&signature=" + md5(params + "&passphrase=" + PASSPHRASE));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    // --- checkout side -----------------------------------------------------

    @Test
    void checkout_buildsDocumentedFieldOrder_withVerifiableSignature() {
        Product product = fixtures.product("PF-CO1", "SKU-PF-CO1", new BigDecimal("250.00"), 5);
        User buyer = fixtures.customerWithCart("pf-buyer-co1", product, 1);
        Long orderId = orderService.placeOrder(buyer.getId()).id();

        var checkout = payfastCheckoutService.createCheckout(orderId, buyer.getId(),
                new com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest(
                        "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
                        null, "Gqeberha", "Eastern Cape", "6001"));

        assertThat(checkout.processUrl()).contains("sandbox.payfast.co.za/eng/process");
        assertThat(checkout.fields().keySet()).containsExactly(
                "merchant_id", "merchant_key", "return_url", "cancel_url", "notify_url",
                "name_first", "email_address", "m_payment_id", "amount", "item_name",
                "signature");
        assertThat(checkout.fields().get("m_payment_id")).isEqualTo(String.valueOf(orderId));
        assertThat(checkout.fields().get("amount")).isEqualTo("250.00");

        // The signature must verify under the same rules the fields claim.
        var unsigned = new java.util.LinkedHashMap<>(checkout.fields());
        String signature = unsigned.remove("signature");
        assertThat(PayfastSignature.sign(unsigned, PASSPHRASE)).isEqualTo(signature);

        // And the address landed in the same transaction.
        assertThat(orderRepository.findById(orderId).orElseThrow().getAddressLine1())
                .isEqualTo("12 Milkwood Lane");
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
