package com.marketplace.api.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The payout terms as ONE source of truth: version, the numbers, and the
 * sentence built from them. The vendor settings screen, the acceptance
 * record, and the public fees endpoint all read from here, so the rate a
 * vendor accepts, the rate the ledger charges, and the rate How It Works
 * displays are the same config value by construction.
 *
 * Bump app.payouts.terms-version on ANY change to the wording below or the
 * numbers it quotes — that is what forces re-acceptance.
 */
@Component
public class PayoutTerms {

    private final BigDecimal commissionRate;
    private final int payoutWindowDays;
    private final int version;

    public PayoutTerms(@Value("${app.payouts.commission-rate}") BigDecimal commissionRate,
                       @Value("${app.payouts.payout-window-days}") int payoutWindowDays,
                       @Value("${app.payouts.terms-version}") int version) {
        this.commissionRate = commissionRate;
        this.payoutWindowDays = payoutWindowDays;
        this.version = version;
    }

    public int version() {
        return version;
    }

    public int payoutWindowDays() {
        return payoutWindowDays;
    }

    /** "12.5" for 0.125 — display form, trailing zeros stripped. */
    public String commissionPercentDisplay() {
        return commissionRate.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    /**
     * The sentence a vendor accepts. Built, never hardcoded in a template:
     * both numbers come from config, and the ledger charges the same
     * commission-rate property this quotes.
     */
    public String text() {
        return "eRestyu remits your share of each sale (item total minus "
                + commissionPercentDisplay() + "% commission; delivery fees pass through in full) "
                + "by EFT to your nominated account within " + payoutWindowDays
                + " days of the weekly payout run following delivery confirmation.";
    }
}
