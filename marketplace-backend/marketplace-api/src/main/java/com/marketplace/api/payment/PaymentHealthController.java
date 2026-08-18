package com.marketplace.api.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops diagnosability for checkout 502s. Public on purpose: it returns no
 * secrets, and the live failure mode is "shoppers cannot pay" — a status
 * page that requires a JWT is not reachable from the incident.
 */
@RestController
public class PaymentHealthController {

    private final PaymentHealth health;

    public PaymentHealthController(PaymentHealth health) {
        this.health = health;
    }

    @GetMapping("/api/v1/payments/health")
    public PaymentHealth.Snapshot health() {
        return health.snapshot();
    }
}
