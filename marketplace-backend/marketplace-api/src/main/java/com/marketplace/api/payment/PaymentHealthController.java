package com.marketplace.api.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops diagnosability for checkout 502s. Public on purpose: it returns no
 * secrets, and the live failure mode is "shoppers cannot pay" — a status
 * page that requires a JWT is not reachable from the incident.
 *
 * checkoutOpenTo ("everyone" | "admins") is what the storefront reads to show
 * "checkout opens soon" instead of a Pay button that would only answer 409.
 * It is a convenience for the UI; the server enforces the rule regardless.
 */
@RestController
public class PaymentHealthController {

    public record HealthResponse(
            String provider,
            String mode,
            boolean configured,
            String lastErrorType,
            String checkoutOpenTo
    ) {}

    private final PaymentHealth health;
    private final CheckoutPolicy checkoutPolicy;

    public PaymentHealthController(PaymentHealth health, CheckoutPolicy checkoutPolicy) {
        this.health = health;
        this.checkoutPolicy = checkoutPolicy;
    }

    @GetMapping("/api/v1/payments/health")
    public HealthResponse health() {
        PaymentHealth.Snapshot s = health.snapshot();
        return new HealthResponse(s.provider(), s.mode(), s.configured(), s.lastErrorType(),
                checkoutPolicy.openTo());
    }
}
