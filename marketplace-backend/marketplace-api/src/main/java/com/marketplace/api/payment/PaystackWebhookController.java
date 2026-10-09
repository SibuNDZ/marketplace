package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.payment.PaymentEventService.ProviderPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/payments/paystack/webhook — UNAUTHENTICATED by design, like
 * the other three providers' endpoints; authenticity comes from
 * x-paystack-signature (see PaystackSignature). Needs its own permitAll
 * carve-out in SecurityConfig.
 *
 * Status codes follow the house rule:
 *   400  signature failure
 *   200  everything after successful verification, including unknown event
 *        types and business anomalies. Paystack retries non-200s for 72 hours
 *        in live mode, and retrying cannot fix an anomaly.
 *
 * The order is identified by the reference, which only our checkout creates
 * ("ERY-{orderId}-..."). Paystack delivers every charge.success on the
 * account to this one URL, including payment pages and anything else run on
 * the same account, so a charge whose reference we did not create is
 * acknowledged and ignored rather than matched to an order by metadata.
 *
 * This controller deliberately does NOT load the order. The amount check
 * runs inside PaymentEventService, after the row lock, against the committed
 * order: an order loaded here first would be the stale instance the lock
 * query hands back (open-in-view keeps one EntityManager per request).
 */
@RestController
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);

    static final String CHARGE_SUCCESS = "charge.success";

    private final PaymentEventService eventService;
    private final ObjectMapper objectMapper;
    private final String secretKey;

    public PaystackWebhookController(PaymentEventService eventService,
                                     ObjectMapper objectMapper,
                                     @Value("${app.paystack.secret-key:}") String secretKey) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey.trim();
    }

    @PostMapping("/api/v1/payments/paystack/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String rawBody,
                                        @RequestHeader(value = "x-paystack-signature", required = false)
                                        String signature) {
        // A blank key fails verify() too, so a non-Paystack deploy rejects
        // every delivery with 400 instead of trusting an unsigned one.
        if (!PaystackSignature.verify(rawBody, signature, secretKey)) {
            log.warn("Paystack webhook rejected: signature verification failed");
            return ResponseEntity.badRequest().build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.error("Paystack webhook passed signature verification but was not valid JSON", e);
            return ResponseEntity.ok().build();
        }

        String event = root.path("event").asText("");
        JsonNode data = root.path("data");
        if (!CHARGE_SUCCESS.equals(event)) {
            log.debug("Paystack webhook event {} not handled - acknowledging", event);
            return ResponseEntity.ok().build();
        }

        String reference = data.path("reference").asText("");
        if (!"success".equals(data.path("status").asText())) {
            log.info("Paystack {} with status '{}' for reference {} - no transition",
                    CHARGE_SUCCESS, data.path("status").asText(), reference);
            return ResponseEntity.ok().build();
        }

        Long orderId = orderIdFromReference(reference);
        if (orderId == null) {
            // Money landed on the account, but not through our checkout. If a
            // human meant it for an order, they reconcile it by hand.
            log.warn("Paystack {} with reference '{}' was not created by the marketplace checkout "
                    + "- not matched to any order", CHARGE_SUCCESS, reference);
            return ResponseEntity.ok().build();
        }

        Long fromMetadata = orderIdFromMetadata(data);
        if (fromMetadata != null && !fromMetadata.equals(orderId)) {
            // Our checkout always writes the same id into both, so this is
            // never a normal delivery.
            log.error("Paystack {} reference {} names order {} but metadata names order {} "
                    + "- MANUAL REVIEW REQUIRED, order NOT transitioned",
                    CHARGE_SUCCESS, reference, orderId, fromMetadata);
            return ResponseEntity.ok().build();
        }

        Long settles = settlingAmountCents(data);
        if (settles == null) {
            log.error("Paystack {} reference {} for order {} has no readable amount "
                    + "- MANUAL REVIEW REQUIRED, order NOT transitioned", CHARGE_SUCCESS, reference, orderId);
            return ResponseEntity.ok().build();
        }

        eventService.handleCheckoutCompleted(orderId, "Paystack",
                new ProviderPayment(reference, settles, data.path("currency").asText("")));
        return ResponseEntity.ok().build();
    }

    /**
     * "ERY-{orderId}-{random}" as generated by PaystackCheckoutService, or
     * null for any reference our checkout did not create.
     */
    static Long orderIdFromReference(String reference) {
        if (reference == null || !reference.startsWith(PaystackCheckoutService.REFERENCE_PREFIX)) {
            return null;
        }
        String rest = reference.substring(PaystackCheckoutService.REFERENCE_PREFIX.length());
        int dash = rest.indexOf('-');
        if (dash <= 0 || dash == rest.length() - 1) {
            return null;
        }
        return parseLong(rest.substring(0, dash));
    }

    /**
     * data.metadata.orderId, used only as a cross-check on the reference.
     * Paystack documents metadata as a stringified JSON object on some
     * endpoints and returns "" or null when none was set; accept every shape.
     */
    Long orderIdFromMetadata(JsonNode data) {
        JsonNode metadata = data.path("metadata");
        if (metadata.isTextual()) {
            try {
                metadata = objectMapper.readTree(metadata.asText());
            } catch (Exception e) {
                return null;
            }
        }
        if (metadata == null) {
            return null;
        }
        return parseLong(metadata.path(PaystackCheckoutService.ORDER_ID_KEY).asText(null));
    }

    /**
     * The amount that settles the order, in cents. Normally data.amount. When
     * the account passes Paystack's fee on to the customer, data.amount is
     * grossed up and data.requested_amount holds what our checkout asked for;
     * the order is settled when the customer paid at least that much. A
     * partial debit (amount below requested_amount) returns the smaller
     * figure, so the amount check refuses it.
     */
    static Long settlingAmountCents(JsonNode data) {
        Long amount = cents(data.path("amount"));
        if (amount == null) {
            return null;
        }
        Long requested = cents(data.path("requested_amount"));
        if (requested == null) {
            return amount;
        }
        return amount >= requested ? requested : amount;
    }

    private static Long cents(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            return parseLong(node.asText());
        }
        return null;
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
