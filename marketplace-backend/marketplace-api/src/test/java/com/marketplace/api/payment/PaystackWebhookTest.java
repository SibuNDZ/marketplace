package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Paystack webhook gauntlet, end to end through the unauthenticated
 * endpoint (the permitAll carve-out is part of what is under test).
 * Signatures come from PaystackSignatureTest's doc-derived HMAC, never from
 * PaystackSignature itself.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PaystackWebhookTest {

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
    static final String KEY = PaystackSignatureTest.KEY;

    @Autowired MockMvc                      mockMvc;
    @Autowired OrderService                 orderService;
    @Autowired OrderRepository              orderRepository;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired TestFixtures                 fixtures;
    @Autowired PlatformTransactionManager   transactionManager;
    @Autowired JdbcTemplate                 jdbcTemplate;

    // --- helpers -----------------------------------------------------------

    /** Stands in for the vendor shipping the order after it was paid. */
    private void markShipped(Long orderId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                jdbcTemplate.update("UPDATE orders SET status = 'SHIPPED' WHERE id = ?", orderId));
    }

    private Long placedOrder(String tag, String price) {
        Product product = fixtures.product("PS-" + tag, "SKU-PS-" + tag, new BigDecimal(price), 5);
        User buyer = fixtures.customerWithCart("ps-buyer-" + tag, product, 1);
        return orderService.placeOrder(buyer.getId()).id();
    }

    private long totalCents(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getTotalAmount()
                .multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    private String successBody(Long orderId) {
        return successBody(orderId, totalCents(orderId), "ZAR");
    }

    private static String successBody(Long orderId, long cents, String currency) {
        return successBody(orderId, "ERY-" + orderId + "-abc123def456", cents, currency);
    }

    private static String successBody(Long orderId, String reference, long cents, String currency) {
        return "{\"event\":\"charge.success\",\"data\":{"
                + "\"id\":" + (4_000_000_000L + orderId) + ",\"domain\":\"test\",\"status\":\"success\","
                + "\"reference\":\"" + reference + "\","
                + "\"amount\":" + cents + ",\"currency\":\"" + currency + "\","
                + "\"metadata\":{\"orderId\":\"" + orderId + "\",\"orderNumber\":\"ORD-X\"}}}";
    }

    private void postWebhook(String body, String signature, int expectedStatus) throws Exception {
        var req = post("/api/v1/payments/paystack/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (signature != null) {
            req.header("x-paystack-signature", signature);
        }
        mockMvc.perform(req).andExpect(status().is(expectedStatus));
    }

    private void postSigned(String body, int expectedStatus) throws Exception {
        postWebhook(body, PaystackSignatureTest.docHmac(body, KEY), expectedStatus);
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private String paymentReferenceOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getPaymentReference();
    }

    // --- the gauntlet ------------------------------------------------------

    @Test
    void chargeSuccess_flipsPendingToPaid_withPaystackAuditNote() throws Exception {
        Long orderId = placedOrder("OK1", "150.00");
        postSigned(successBody(orderId), 200);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentReferenceOf(orderId)).isEqualTo("ERY-" + orderId + "-abc123def456");
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .filteredOn(h -> h.getToStatus() == OrderStatus.PAID)
                .singleElement()
                .satisfies(h -> assertThat(h.getNote()).isEqualTo("Payment completed (Paystack)"));
    }

    @Test
    void duplicateDelivery_isIdempotent() throws Exception {
        Long orderId = placedOrder("DUP1", "120.00");
        String body = successBody(orderId);
        postSigned(body, 200);
        postSigned(body, 200);

        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .filteredOn(h -> h.getToStatus() == OrderStatus.PAID)
                .hasSize(1);
    }

    @Test
    void tamperedBody_400_orderUntouched() throws Exception {
        Long orderId = placedOrder("TMP1", "80.00");
        String body = successBody(orderId);
        String signature = PaystackSignatureTest.docHmac(body, KEY);

        postWebhook(body.replace("\"status\":\"success\"", "\"status\":\"success\" "), signature, 400);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void wrongKey_400_orderUntouched() throws Exception {
        Long orderId = placedOrder("KEY1", "80.00");
        String body = successBody(orderId);
        postWebhook(body, PaystackSignatureTest.docHmac(body, "sk_live_someone_else"), 400);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void missingSignatureHeader_400() throws Exception {
        Long orderId = placedOrder("HDR1", "55.00");
        postWebhook(successBody(orderId), null, 400);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void amountMismatch_200_orderStaysPending() throws Exception {
        // Correctly signed, but for R1 on a R95 order. Only reachable through
        // a bug on our side or Paystack's, and exactly then it must not ship.
        Long orderId = placedOrder("AMT1", "95.00");
        postSigned(successBody(orderId, 100, "ZAR"), 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void currencyMismatch_200_orderStaysPending() throws Exception {
        Long orderId = placedOrder("CUR1", "95.00");
        postSigned(successBody(orderId, totalCents(orderId), "NGN"), 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void otherEvent_200_orderUntouched() throws Exception {
        Long orderId = placedOrder("UNK1", "65.00");
        postSigned(successBody(orderId).replace("charge.success", "refund.processed"), 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void chargeSuccessWithFailedStatus_200_orderUntouched() throws Exception {
        Long orderId = placedOrder("FAIL1", "65.00");
        postSigned(successBody(orderId).replace("\"status\":\"success\"", "\"status\":\"failed\""), 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void emptyMetadata_referenceAloneResolvesOrder() throws Exception {
        // Paystack returns metadata as "" when a transaction has none.
        Long orderId = placedOrder("REF1", "75.00");
        String body = successBody(orderId)
                .replaceAll("\"metadata\":\\{[^}]*}", "\"metadata\":\"\"");
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void stringifiedMetadata_agreeingWithReference_isAccepted() throws Exception {
        Long orderId = placedOrder("STR1", "75.00");
        String stringified = new ObjectMapper().writeValueAsString("{\"orderId\":\"" + orderId + "\"}");
        String body = successBody(orderId)
                .replaceAll("\"metadata\":\\{[^}]*}", Matcher.quoteReplacement("\"metadata\":" + stringified));
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void stringifiedMetadata_disagreeingWithReference_orderUntouched() throws Exception {
        // Proves the stringified shape is really parsed: if it were ignored,
        // the reference alone would mark the order PAID.
        Long orderId = placedOrder("STR2", "75.00");
        String stringified = new ObjectMapper().writeValueAsString("{\"orderId\":\"" + (orderId + 1000) + "\"}");
        String body = successBody(orderId)
                .replaceAll("\"metadata\":\\{[^}]*}", Matcher.quoteReplacement("\"metadata\":" + stringified));
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void foreignReference_withMatchingOrderIdMetadata_isIgnored() throws Exception {
        // A charge our checkout did not create (another app or a payment page
        // on the same Paystack account) whose metadata happens to say orderId
        // and whose amount happens to match. Paystack sends it here anyway.
        Long orderId = placedOrder("FOR1", "75.00");
        postSigned(successBody(orderId, "T1234567890abc", totalCents(orderId), "ZAR"), 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void metadataDisagreeingWithReference_orderUntouched() throws Exception {
        Long orderId = placedOrder("DIS1", "75.00");
        Long otherOrderId = placedOrder("DIS2", "75.00");
        String body = successBody(orderId).replace(
                "\"orderId\":\"" + orderId + "\"", "\"orderId\":\"" + otherOrderId + "\"");
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(otherOrderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void feePassedToCustomer_requestedAmountSettlesTheOrder() throws Exception {
        // With "pass fees to customer" on, amount is grossed up and
        // requested_amount is what our checkout asked for.
        Long orderId = placedOrder("FEE1", "100.00");
        long total = totalCents(orderId);
        String body = successBody(orderId, total + 390, "ZAR")
                .replace("\"currency\"", "\"requested_amount\":" + total + ",\"fees\":390,\"currency\"");
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void partialDebit_belowRequestedAmount_orderUntouched() throws Exception {
        Long orderId = placedOrder("PART1", "100.00");
        long total = totalCents(orderId);
        String body = successBody(orderId, total - 5000, "ZAR")
                .replace("\"currency\"", "\"requested_amount\":" + total + ",\"currency\"");
        postSigned(body, 200);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void secondPaidTransaction_forAPaidOrder_raisesRefundAlert_noSecondTransition(CapturedOutput output)
            throws Exception {
        // Two checkouts opened for one order and both paid (for example an
        // EFT that settled after the customer paid again by card). The
        // database looks the same either way, so the alert line IS the
        // behaviour under test: it is the refund queue.
        Long orderId = placedOrder("TWO1", "120.00");
        long total = totalCents(orderId);
        postSigned(successBody(orderId, "ERY-" + orderId + "-aaaaaaaaaaaa", total, "ZAR"), 200);
        postSigned(successBody(orderId, "ERY-" + orderId + "-bbbbbbbbbbbb", total, "ZAR"), 200);

        assertThat(paymentReferenceOf(orderId)).isEqualTo("ERY-" + orderId + "-aaaaaaaaaaaa");
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .filteredOn(h -> h.getToStatus() == OrderStatus.PAID)
                .hasSize(1);
        assertThat(output.getAll())
                .contains("DUPLICATE PAYMENT FOR ORDER " + orderId + " ")
                .contains("MANUAL REFUND REQUIRED");
    }

    @Test
    void sameReferenceRedelivered_isNotAnAlert(CapturedOutput output) throws Exception {
        Long orderId = placedOrder("RED1", "120.00");
        String body = successBody(orderId);
        postSigned(body, 200);
        postSigned(body, 200);

        assertThat(output.getAll())
                .doesNotContain("DUPLICATE PAYMENT FOR ORDER " + orderId + " ")
                .doesNotContain("NON-PAYABLE ORDER " + orderId + " ");
    }

    @Test
    void sameReferenceRedelivered_afterShipping_isNotARefund(CapturedOutput output) throws Exception {
        // A lost 200 or a dashboard resend can arrive days later, after the
        // vendor shipped. It is the payment that already settled the order,
        // so it must not land in the refund queue.
        Long orderId = placedOrder("SHIP1", "120.00");
        String body = successBody(orderId);
        postSigned(body, 200);
        markShipped(orderId);

        postSigned(body, 200);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.SHIPPED);
        assertThat(output.getAll()).doesNotContain("NON-PAYABLE ORDER " + orderId + " ");
    }

    @Test
    void differentPayment_afterShipping_stillRaisesRefundAlert(CapturedOutput output) throws Exception {
        Long orderId = placedOrder("SHIP2", "120.00");
        long total = totalCents(orderId);
        postSigned(successBody(orderId, "ERY-" + orderId + "-aaaaaaaaaaaa", total, "ZAR"), 200);
        markShipped(orderId);

        postSigned(successBody(orderId, "ERY-" + orderId + "-bbbbbbbbbbbb", total, "ZAR"), 200);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.SHIPPED);
        assertThat(output.getAll())
                .contains("NON-PAYABLE ORDER " + orderId + " ")
                .contains("MANUAL REFUND REQUIRED");
    }

    @Test
    void unresolvableOrder_200_noCrash() throws Exception {
        String body = "{\"event\":\"charge.success\",\"data\":{\"status\":\"success\","
                + "\"reference\":\"someone-elses-ref\",\"amount\":100,\"currency\":\"ZAR\",\"metadata\":{}}}";
        postSigned(body, 200);
    }

    @Test
    void unknownOrder_200_noCrash() throws Exception {
        postSigned(successBody(999_999_999L, 100, "ZAR"), 200);
    }
}
