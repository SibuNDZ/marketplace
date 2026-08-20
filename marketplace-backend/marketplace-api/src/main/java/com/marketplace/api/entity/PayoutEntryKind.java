package com.marketplace.api.entity;

/**
 * What a ledger row IS, permanently — as opposed to where it is in its
 * lifecycle (status), which moves.
 *
 * Kept separate from status deliberately: an adjustment that gets paid out
 * would otherwise lose its adjustment-ness the moment its status flips to
 * PAID, and the one-primary-per-(order, vendor) unique index would collide.
 * The partial unique index in V27 is scoped to kind = 'PRIMARY'.
 *
 * Lockstep with vendor_payout_entries_kind_check in V27.
 */
public enum PayoutEntryKind {
    /** The one entry per (order, vendor) written when the order goes PAID. */
    PRIMARY,
    /**
     * A correction referencing the same pair: partial refund, or a clawback
     * when a full refund lands after the vendor was already paid out.
     * Amounts may be negative.
     */
    ADJUSTMENT
}
