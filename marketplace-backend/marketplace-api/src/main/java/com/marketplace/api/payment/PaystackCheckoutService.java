package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Initializes Paystack transactions for PENDING orders.
 *
 * Shape mirrors YOCO: Paystack hosts the payment page and hands back an
 * authorization_url, so the response to the frontend is {checkoutUrl} and the
 * existing redirect branch in CartPage handles it with no frontend change.
 *
 * The reference is what ties a charge.success webhook back to an order: WE
 * generate it as "ERY-{orderId}-{random}" and Paystack echoes it on every
 * event. metadata.orderId carries the same id as a cross-check and for the
 * dashboard. The reference is unique per payment attempt, not per order,
 * because Paystack rejects a reused reference and paying a still-PENDING
 * order twice is a legitimate flow (see YocoCheckoutService on idempotency
 * scope); a second attempt that is ALSO paid is caught by
 * PaymentEventService as a duplicate payment.
 *
 * Money: Paystack takes the amount in subunits (cents) and we pin ZAR rather
 * than relying on the integration default. One amount, no line items, same
 * reasoning as Yoco: order.totalAmount is the single charged figure.
 *
 * Paystack has one callback_url where Yoco has three. A cancelled page is
 * reached through metadata.cancel_action instead; a failed card stays on
 * Paystack's page for a retry.
 */
@Service
public class PaystackCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PaystackCheckoutService.class);

    /** Metadata key carrying the order id. The webhook reads this exact key. */
    static final String ORDER_ID_KEY = "orderId";

    /** Reference prefix; the webhook matches only references that carry it. */
    static final String REFERENCE_PREFIX = "ERY-";

    private final CheckoutPreparation checkoutPreparation;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI initializeUrl;
    private final String secretKey;
    private final String successUrl;
    private final String cancelUrl;

    public PaystackCheckoutService(CheckoutPreparation checkoutPreparation,
                                   ObjectMapper objectMapper,
                                   @Value("${app.paystack.initialize-url}") String initializeUrl,
                                   @Value("${app.paystack.secret-key:}") String secretKey,
                                   @Value("${app.paystack.success-url}") String successUrl,
                                   @Value("${app.paystack.cancel-url}") String cancelUrl) {
        this.checkoutPreparation = checkoutPreparation;
        this.objectMapper = objectMapper;
        this.initializeUrl = URI.create(initializeUrl);
        this.secretKey = secretKey.trim();
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
    }

    /**
     * Same transaction boundary as the other providers: CheckoutPreparation
     * writes the address and enforces ownership + PENDING, then the provider
     * call happens inside that transaction.
     */
    @Transactional
    public String createCheckout(Long orderId, Long userId, ShippingAddressRequest shipping) {
        Order order = checkoutPreparation.attachShipping(orderId, userId, shipping);

        ObjectNode body = objectMapper.createObjectNode();
        // Paystack requires the customer's email; the account email is the
        // one the order confirmation already goes to.
        body.put("email", order.getUser().getEmail());
        body.put("amount", String.valueOf(Money.toCents(order.getTotalAmount())));
        body.put("currency", "ZAR");
        body.put("reference", newReference(order.getId()));
        body.put("callback_url", successUrl + "?order=" + order.getId());
        ObjectNode metadata = body.putObject("metadata");
        metadata.put(ORDER_ID_KEY, String.valueOf(order.getId()));
        metadata.put("orderNumber", order.getOrderNumber());
        metadata.put("cancel_action", cancelUrl + "?order=" + order.getId());

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw PaymentExceptions.unavailable(
                    "Failed to serialise Paystack initialize request for order " + orderId, e);
        }

        return post(json, orderId);
    }

    static String newReference(Long orderId) {
        return REFERENCE_PREFIX + orderId + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * Paystack has no Idempotency-Key header; the unique reference plays that
     * role, since Paystack refuses a second initialize with the same one. So
     * the single connect-error retry reuses this attempt's reference, and any
     * ambiguous outcome (timeout mid-response, 5xx) fails the attempt rather
     * than risk a second checkout.
     */
    private String post(String json, Long orderId) {
        HttpRequest request = HttpRequest.newBuilder(initializeUrl)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = send(request);
        } catch (ConnectException | HttpConnectTimeoutException neverArrived) {
            log.warn("Paystack initialize connect failure for order {}, retrying once", orderId);
            try {
                response = send(request);
            } catch (Exception retryFailed) {
                throw PaymentExceptions.unavailable(
                        "Failed to reach Paystack for order " + orderId, retryFailed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PaymentExceptions.unavailable(
                    "Interrupted initializing Paystack transaction for order " + orderId, e);
        } catch (Exception e) {
            throw PaymentExceptions.unavailable(
                    "Failed to initialize Paystack transaction for order " + orderId, e);
        }

        if (response.statusCode() / 100 != 2) {
            // Body carries Paystack's message naming the rejected field; no card data.
            log.error("Paystack initialize failed for order {}: HTTP {} body '{}'",
                    orderId, response.statusCode(), response.body());
            throw PaymentExceptions.fromHttpStatus(
                    "Paystack returned HTTP " + response.statusCode() + " for order " + orderId,
                    response.statusCode());
        }

        String authorizationUrl;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            authorizationUrl = root.path("data").path("authorization_url").asText(null);
        } catch (Exception e) {
            throw PaymentExceptions.unavailable(
                    "Unparseable Paystack initialize response for order " + orderId, e);
        }
        if (authorizationUrl == null || authorizationUrl.isBlank()) {
            throw PaymentExceptions.unavailable(
                    "Paystack initialize response had no authorization_url for order " + orderId, null);
        }
        return authorizationUrl;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
