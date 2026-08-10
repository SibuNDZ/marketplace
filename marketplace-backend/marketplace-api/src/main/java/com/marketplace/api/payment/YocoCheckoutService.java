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
 * Creates Yoco Checkouts for PENDING orders.
 *
 * Shape mirrors STRIPE, not PayFast: Yoco hosts the payment page and hands
 * back a redirectUrl, so the response to the frontend is {checkoutUrl} and
 * the existing redirect branch in CartPage handles it with no frontend change.
 *
 * THE METADATA IS LOAD-BEARING. Verified against Yoco's Checkout API OpenAPI
 * description: the payment.succeeded webhook payload carries
 * {amount, currency, id, metadata, mode, paymentMethodDetails, status, type}
 * and NO checkout reference at all — not the checkout id, not clientReferenceId,
 * not externalId. metadata is therefore the ONLY route from a delivered
 * webhook back to an order. clientReferenceId and externalId are still sent
 * because they show up in Yoco's dashboard and make manual reconciliation
 * possible, but nothing in our code may depend on them coming back.
 *
 * Money: Yoco takes cents (integer) and ZAR only. Delivery fees are already
 * inside order.totalAmount, and unlike Stripe there is no line-item sum to
 * keep in agreement — one amount, one charge. lineItems are display-only in
 * Yoco's API and are deliberately not sent: they would be a second source of
 * truth for money that must equal totalAmount anyway.
 */
@Service
public class YocoCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(YocoCheckoutService.class);

    /** Metadata key carrying the order id. The webhook reads this exact key. */
    static final String ORDER_ID_KEY = "orderId";

    private final CheckoutPreparation checkoutPreparation;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI checkoutsUrl;
    private final String secretKey;
    private final String successUrl;
    private final String cancelUrl;
    private final String failureUrl;

    public YocoCheckoutService(CheckoutPreparation checkoutPreparation,
                               ObjectMapper objectMapper,
                               @Value("${app.yoco.checkouts-url}") String checkoutsUrl,
                               @Value("${app.yoco.secret-key:}") String secretKey,
                               @Value("${app.yoco.success-url}") String successUrl,
                               @Value("${app.yoco.cancel-url}") String cancelUrl,
                               @Value("${app.yoco.failure-url}") String failureUrl) {
        this.checkoutPreparation = checkoutPreparation;
        this.objectMapper = objectMapper;
        this.checkoutsUrl = URI.create(checkoutsUrl);
        this.secretKey = secretKey.trim();
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
        this.failureUrl = failureUrl;
    }

    /**
     * Same transaction boundary as the other two providers: CheckoutPreparation
     * writes the address and enforces ownership + PENDING, then the provider
     * call happens inside that transaction. Address saved and checkout created
     * together, or neither.
     */
    @Transactional
    public String createCheckout(Long orderId, Long userId, ShippingAddressRequest shipping) {
        Order order = checkoutPreparation.attachShipping(orderId, userId, shipping);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("amount", Money.toCents(order.getTotalAmount()));
        body.put("currency", "ZAR");
        body.put("successUrl", successUrl + "?order=" + order.getId());
        body.put("cancelUrl", cancelUrl + "?order=" + order.getId());
        body.put("failureUrl", failureUrl + "?order=" + order.getId());
        body.put("clientReferenceId", String.valueOf(order.getId()));
        body.put("externalId", order.getOrderNumber());
        ObjectNode metadata = body.putObject("metadata");
        metadata.put(ORDER_ID_KEY, String.valueOf(order.getId()));
        metadata.put("orderNumber", order.getOrderNumber());

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new PaymentExceptions.PaymentProviderException(
                    "Failed to serialise Yoco checkout request for order " + orderId, e);
        }

        return post(json, orderId);
    }

    /**
     * Idempotency-Key scope is ONE user-initiated payment attempt, not the
     * order. It is generated per invocation and reused only for the single
     * connect-error retry below.
     *
     * Deliberately NOT the order id: paying a still-PENDING order twice is a
     * legitimate flow (the customer abandoned the first Yoco page and came
     * back), and an order-scoped key would replay the FIRST checkout — quite
     * possibly one that has since expired, leaving the customer with a dead
     * link and no way to pay. Two live checkouts for one order is harmless
     * (only one can be completed; the order is marked PAID idempotently);
     * a customer who cannot pay at all is not.
     */
    private String post(String json, Long orderId) {
        String idempotencyKey = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(checkoutsUrl)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = send(request);
        } catch (ConnectException | HttpConnectTimeoutException neverArrived) {
            // The request did not reach Yoco, so no checkout can exist and a
            // retry cannot duplicate a charge. This is the ONLY retryable
            // case: a timeout mid-response, a 5xx, or any other ambiguous
            // outcome might mean the checkout WAS created, and we would rather
            // fail the payment attempt than risk a second one.
            log.warn("Yoco checkout connect failure for order {}, retrying once", orderId);
            try {
                response = send(request);
            } catch (Exception retryFailed) {
                throw new PaymentExceptions.PaymentProviderException(
                        "Failed to reach Yoco for order " + orderId, retryFailed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentExceptions.PaymentProviderException(
                    "Interrupted creating Yoco checkout for order " + orderId, e);
        } catch (Exception e) {
            throw new PaymentExceptions.PaymentProviderException(
                    "Failed to create Yoco checkout for order " + orderId, e);
        }

        if (response.statusCode() / 100 != 2) {
            // Body may name the field Yoco rejected; it carries no card data.
            log.error("Yoco checkout creation failed for order {}: HTTP {} body '{}'",
                    orderId, response.statusCode(), response.body());
            throw new PaymentExceptions.PaymentProviderException(
                    "Yoco returned HTTP " + response.statusCode() + " for order " + orderId, null);
        }

        String redirectUrl;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            redirectUrl = root.path("redirectUrl").asText(null);
        } catch (Exception e) {
            throw new PaymentExceptions.PaymentProviderException(
                    "Unparseable Yoco checkout response for order " + orderId, e);
        }
        if (redirectUrl == null || redirectUrl.isBlank()) {
            throw new PaymentExceptions.PaymentProviderException(
                    "Yoco checkout response had no redirectUrl for order " + orderId, null);
        }
        return redirectUrl;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
