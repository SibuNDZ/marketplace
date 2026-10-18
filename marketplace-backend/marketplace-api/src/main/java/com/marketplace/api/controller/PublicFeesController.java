package com.marketplace.api.controller;

import com.marketplace.api.payout.PayoutTerms;
import com.marketplace.api.payout.SellingGate;
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
            int payoutWindowDays,
            /**
             * Whether checkout is actually gated on vendor onboarding and
             * approval. Public copy that describes the gate (the seller
             * terms' verification section) renders only when this is true,
             * so the page tells the truth in both flag states and flipping
             * the flag changes the copy without a deploy.
             */
            boolean sellingGateEnabled,
            /**
             * Whether the Paystack subaccount rollout is switched on: bank
             * validation on save, subaccounts on approve, split settlement.
             * The identity inputs on the vendor card render only when true,
             * because only then is "used once to verify your bank account
             * with our payment provider" a true sentence.
             */
            boolean splitEnabled
    ) {}

    private final PayoutTerms terms;
    private final SellingGate gate;
    private final boolean commissionConfirmed;
    private final boolean splitEnabled;

    public PublicFeesController(
            PayoutTerms terms,
            SellingGate gate,
            @Value("${app.payouts.commission-confirmed}") boolean commissionConfirmed,
            @Value("${app.paystack.split-enabled:false}") boolean splitEnabled) {
        this.terms = terms;
        this.gate = gate;
        this.commissionConfirmed = commissionConfirmed;
        this.splitEnabled = splitEnabled;
    }

    @GetMapping
    public FeesResponse fees() {
        return new FeesResponse(
                commissionConfirmed,
                terms.commissionPercentDisplay(),
                terms.payoutWindowDays(),
                gate.isEnabled(),
                splitEnabled);
    }
}
