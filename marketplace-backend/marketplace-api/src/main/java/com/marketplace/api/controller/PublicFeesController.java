package com.marketplace.api.controller;

import com.marketplace.api.payout.PayoutTerms;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public fee numbers for the How It Works "Fees" section — a vendor should
 * meet the commission rate in public copy before meeting it inside a terms
 * checkbox, and both must be the SAME config value (PayoutTerms).
 *
 * commissionLive is the commission-confirmed flag: while the rate was a
 * config placeholder, the public page must not present it as a live fee —
 * the same honest-signals rule that keeps fabricated urgency off the PDP.
 * It used to mirror the selling gate; the two split when the owner decided
 * the rate (10%, 2026-08-30) while the gate stayed off — the number being
 * real is a display fact, the gate blocking checkout is a rollout event.
 */
@RestController
@RequestMapping("/api/v1/fees")
public class PublicFeesController {

    public record FeesResponse(
            boolean commissionLive,
            String commissionPercent,
            int payoutWindowDays
    ) {}

    private final PayoutTerms terms;
    private final boolean commissionConfirmed;

    public PublicFeesController(
            PayoutTerms terms,
            @Value("${app.payouts.commission-confirmed}") boolean commissionConfirmed) {
        this.terms = terms;
        this.commissionConfirmed = commissionConfirmed;
    }

    @GetMapping
    public FeesResponse fees() {
        return new FeesResponse(
                commissionConfirmed,
                terms.commissionPercentDisplay(),
                terms.payoutWindowDays());
    }
}
