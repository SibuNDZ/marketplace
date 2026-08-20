package com.marketplace.api.controller;

import com.marketplace.api.payout.PayoutTerms;
import com.marketplace.api.payout.SellingGate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public fee numbers for the How It Works "Fees" section — a vendor should
 * meet the commission rate in public copy before meeting it inside a terms
 * checkbox, and both must be the SAME config value (PayoutTerms).
 *
 * commissionLive mirrors the selling gate flag: while the rate is still the
 * config placeholder, the public page must not present it as a live fee —
 * the same honest-signals rule that keeps fabricated urgency off the PDP.
 * The frontend renders the commission sentence only when this is true.
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
    private final SellingGate gate;

    public PublicFeesController(PayoutTerms terms, SellingGate gate) {
        this.terms = terms;
        this.gate = gate;
    }

    @GetMapping
    public FeesResponse fees() {
        return new FeesResponse(
                gate.isEnabled(),
                terms.commissionPercentDisplay(),
                terms.payoutWindowDays());
    }
}
