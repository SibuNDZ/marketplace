package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.security.UserPrincipal;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * Two endpoints, two trust models:
 *
 * POST /api/v1/orders/{id}/pay — authenticated customer starts payment;
 * returns the Stripe-hosted checkout URL. The card never touches this
 * backend (no PCI scope beyond SAQ-A).
 *
 * POST /api/v1/payments/stripe/webhook — UNAUTHENTICATED by design (Stripe
 * has no JWT); authenticity comes from the signature header verified against
 * the webhook secret. Requires a permitAll carve-out in SecurityConfig.
 * Signature failure -> 400 (Stripe will retry; correct for transient secret
 * misconfiguration). Anything after successful verification returns 200 even
 * for business anomalies — those are OUR problems to alert on, and 5xx would
 * just make Stripe hammer the endpoint.
 *
 * Metadata is read from the RAW payload via Jackson, not stripe-java's typed
 * EventDataObjectDeserializer — the deserializer returns an empty Optional
 * whenever the event's API version (set by the dashboard/CLI at endpoint
 * creation) doesn't match the version stripe-java was built against, even
 * though the raw JSON Stripe sent has always contained the field (confirmed
 * in production: evt_1TsQPhDQkBKfcjoqCDgBhi3q hit "without order_id
 * metadata" while `stripe events retrieve` showed metadata.order_id present
 * the whole time). Reading the bytes we already verified the signature
 * against is immune to that drift permanently — no endpoint api_version pin
 * to keep in sync with the next stripe-java bump.
 */
@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final StripeCheckoutService checkoutService;
    private final PaymentEventService eventService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public PaymentController(StripeCheckoutService checkoutService,
                             PaymentEventService eventService,
                             ObjectMapper objectMapper,
                             @Value("${app.stripe.webhook-secret}") String webhookSecret) {
        this.checkoutService = checkoutService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/api/v1/orders/{id}/pay")
    public Map<String, String> pay(@PathVariable Long id,
                                   @AuthenticationPrincipal UserPrincipal me) {
        return Map.of("checkoutUrl", checkoutService.createCheckoutSession(id, me.getId()));
    }

    @PostMapping("/api/v1/payments/stripe/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String payload,
                                        @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed");
            return ResponseEntity.badRequest().build();
        }

        if ("checkout.session.completed".equals(event.getType())) {
            String orderId = extractOrderId(payload);
            if (orderId != null) {
                eventService.handleCheckoutCompleted(Long.parseLong(orderId));
            } else {
                log.error("checkout.session.completed without order_id metadata — "
                        + "event {} needs investigation", event.getId());
            }
        }
        // Unhandled event types: 200. We only subscribed to what we handle,
        // but Stripe dashboards get reconfigured; unknown != error.
        return ResponseEntity.ok().build();
    }

    /**
     * data.object.metadata.order_id from the RAW webhook payload — not the
     * SDK's typed deserialization. Package-private so the version-drift fix
     * is unit-testable against a real-shaped JSON string with no Stripe
     * signature or Spring context required.
     */
    String extractOrderId(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode orderId = root.path("data").path("object").path("metadata").path("order_id");
            return orderId.isMissingNode() || orderId.isNull() ? null : orderId.asText();
        } catch (IOException e) {
            log.error("Failed to parse Stripe webhook payload as JSON", e);
            return null;
        }
    }
}
