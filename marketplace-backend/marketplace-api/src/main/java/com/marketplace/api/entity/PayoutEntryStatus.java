package com.marketplace.api.entity;

/**
 * Lifecycle of a payout ledger entry.
 *
 * LOCKSTEP WARNING (the V7 discipline): this enum and the CHECK constraint
 * vendor_payout_entries_status_check in V27 list the same values. Adding a
 * value here without a migration widening the CHECK means the first write of
 * that value dies in production.
 *
 * PENDING   — owed, awaiting admin approval into a batch.
 * APPROVED  — selected into a payout batch, not yet paid.
 * PAID      — money left the account; payment_reference and paid_at set.
 * VOID      — reversed before any money moved (full refund of an unpaid-out
 *             order). Terminal. The row stays: the ledger is append-only
 *             history, like order_status_history.
 * ADJUSTED  — initial status of an ADJUSTMENT-kind row (partial refund,
 *             clawback). Flows into batches like PENDING does: an adjustment
 *             is settled by including its (possibly negative) amount in the
 *             vendor's next payout, so ADJUSTED -> APPROVED -> PAID.
 */
public enum PayoutEntryStatus {
    PENDING,
    APPROVED,
    PAID,
    VOID,
    ADJUSTED
}
