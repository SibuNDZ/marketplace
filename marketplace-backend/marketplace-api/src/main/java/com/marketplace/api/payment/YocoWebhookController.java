package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * POST /api/v1/payments/yoco/webhook — UNAUTHENTICATED by design, like the
 * Stripe webhook and the PayFast ITN; authenticity comes from the signature.
 * Needs its own permitAll carve-out in SecurityConfig.
 *
 * Status codes follow the house rule established by the other two providers:
 *   400  signature/freshness failure — svix retries, which is what we want for
 *        a transient secret misconfiguration
 *   200  everything after successful verification, including unknown event
 *        types and business anomalies. Those are OUR problems to alert on;
 *        a 5xx would just make the delivery ladder hammer the endpoint.
 *
 * The raw body is taken as @RequestBody String and re-encoded UTF-8 for the
 * HMAC, exactly as the Stripe path does. Yoco sends UTF-8 JSON, so this
 * round-trips byte-identically; it avoids a servlet filter for the only two
 * endpoints that need raw bytes.
 */
@RestController
public class YocoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(YocoWebhookController.class);

    /** Verified against the Checkout API OpenAPI description. */
    static final String PAYMENT_SUCCEEDED = "payment.succeeded";

    private final PaymentEventService eventService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;
    private final Duration tolerance;

    public YocoWebhookController(PaymentEventService eventService,
                                 ObjectMapper objectMapper,
                                 @Value("${app.yoco.webhook-secret:}") String webhookSecret,
                                 @Value("${app.yoco.webhook-tolerance-seconds:180}") long toleranceSeconds) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret.trim();
        this.tolerance = Duration.ofSeconds(toleranceSeconds);
    }

    @PostMapping("/api/v1/payments/yoco/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String rawBody,
                                        @RequestHeader(value = "webhook-id", required = false) String webhookId,
                                        @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "webhook-signature", required = false) String signature) {

        if (!YocoSignature.verify(webhookId, timestamp, rawBody, signature,
                webhookSecret, tolerance, Instant.now())) {
            // Never log the payload or the headers: a rejected delivery is
            // exactly the case where the content is untrusted.
            log.warn("Yoco webhook rejected: signature or timestamp verification failed (webhook-id {})",
                    webhookId);
            return ResponseEntity.badRequest().build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.error("Yoco webhook passed signature verification but was not valid JSON", e);
            return ResponseEntity.ok().build();
        }

        String type = root.path("type").asText("");
        if (!PAYMENT_SUCCEEDED.equals(type)) {
            log.debug("Yoco webhook event type {} not handled — acknowledging", type);
            return ResponseEntity.ok().build();
        }

        Long orderId = extractOrderId(root);
        if (orderId == null) {
            // The webhook carries no checkout reference, so without metadata
            // there is NOTHING to reconcile against — money has moved and we
            // cannot say for which order. This log line is the alert.
            log.error("Yoco {} without {} metadata — event {} needs investigation, "
                            + "payment {} is unreconciled",
                    PAYMENT_SUCCEEDED, YocoCheckoutService.ORDER_ID_KEY,
                    root.path("id").asText("?"), root.path("payload").path("id").asText("?"));
            return ResponseEntity.ok().build();
        }

        eventService.handleCheckoutCompleted(orderId, "Yoco");
        return ResponseEntity.ok().build();
    }

    /**
     * payload.metadata.orderId from the verified body. Package-private so the
     * path is unit-testable against a real-shaped payload with no signature
     * and no Spring context.
     */
    Long extractOrderId(JsonNode root) {
        JsonNode node = root.path("payload").path("metadata").path(YocoCheckoutService.ORDER_ID_KEY);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Long.parseLong(node.asText().trim());
        } catch (NumberFormatException e) {
            log.error("Yoco webhook metadata.{} was not a number: '{}'",
                    YocoCheckoutService.ORDER_ID_KEY, node.asText());
            return null;
        }
    }
}
