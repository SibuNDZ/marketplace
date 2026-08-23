-- V27__vendor_payout_ledger.sql
-- Commission ledger: what eRestyu owes each vendor, written the moment an
-- order goes PAID (vendor-payouts.md, ledger tier).
--
-- Until now this was Tier 0: money lands in one bank account and NOTHING
-- records whose it is. Every day on a collect-then-disburse provider is a day
-- of holding other people's money with no system record — the ledger is the
-- record. It does not move money; it makes the debt queryable.
--
-- Design notes:
--
--  * vendor_id is NOT NULL and has no ON DELETE action (RESTRICT), unlike
--    order_delivery_fees.vendor_id which is SET NULL. Deliberate asymmetry:
--    a fee row is display history and may outlive its vendor, but a payout
--    entry IS a debt to a person — deleting a vendor who is still owed money
--    must fail loudly, not silently orphan the liability.
--
--  * Amounts are NUMERIC(10,2), matching orders.total_amount and
--    fee_at_purchase exactly. One money convention, no second one.
--
--  * kind separates the one PRIMARY entry per (order, vendor) from later
--    ADJUSTMENT rows (partial refunds, clawbacks). The prompt's plain
--    UNIQUE(order_id, vendor_id) cannot hold once adjustments exist — an
--    adjustment is by definition a second row for the same pair — and making
--    the unique key include status breaks the moment an adjustment's status
--    moves (ADJUSTED -> APPROVED -> PAID would collide with the primary).
--    A sibling table was the other option and was rejected: "amount owed"
--    would become a UNION across two tables and the batch/export flow would
--    juggle two entity types. One table + kind keeps sums single-table and
--    the idempotency guard intact.
--
--  * The partial unique index is the loud backstop for webhook retries; the
--    primary idempotency is PaymentEventService's row lock + PAID check.

CREATE TABLE vendor_payout_entries (
    id                 BIGSERIAL PRIMARY KEY,
    order_id           BIGINT NOT NULL REFERENCES orders (id),
    vendor_id          BIGINT NOT NULL REFERENCES users (id),
    kind               VARCHAR(12) NOT NULL DEFAULT 'PRIMARY'
        CONSTRAINT vendor_payout_entries_kind_check
        CHECK (kind IN ('PRIMARY', 'ADJUSTMENT')),

    -- Money, snapshotted at write time. Rates on users may change later;
    -- these never do — same principle as order_items.price_at_purchase.
    item_subtotal      NUMERIC(10, 2) NOT NULL,
    delivery_fee       NUMERIC(10, 2) NOT NULL DEFAULT 0,
    commission_rate    NUMERIC(5, 4)  NOT NULL
        CONSTRAINT vendor_payout_entries_rate_range
        CHECK (commission_rate >= 0 AND commission_rate <= 1),
    commission_amount  NUMERIC(10, 2) NOT NULL,
    -- net_payable = item_subtotal - commission_amount + delivery_fee.
    -- Negative only on ADJUSTMENT rows (clawbacks).
    net_payable        NUMERIC(10, 2) NOT NULL,

    -- Mirrors the V7 enum+CHECK lockstep discipline: the Java enum and this
    -- list change together or the first new-status write dies in production.
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT vendor_payout_entries_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'PAID', 'VOID', 'ADJUSTED')),

    -- Batch linkage arrives with the batches table (payout operations
    -- slice); a plain nullable column until then.
    batch_id           BIGINT,
    payment_reference  VARCHAR(100),
    paid_at            TIMESTAMP,
    -- Why a row exists, when it is not obvious: adjustment reasons,
    -- void causes. NULL on ordinary primary entries.
    note               VARCHAR(500),

    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP
);

-- One PRIMARY entry per vendor per order — the idempotency invariant.
-- Partial: adjustments are additional rows for the same pair by design.
CREATE UNIQUE INDEX uq_vendor_payout_primary
    ON vendor_payout_entries (order_id, vendor_id) WHERE kind = 'PRIMARY';

CREATE INDEX idx_vendor_payout_status        ON vendor_payout_entries (status);
CREATE INDEX idx_vendor_payout_vendor_status ON vendor_payout_entries (vendor_id, status);
CREATE INDEX idx_vendor_payout_batch         ON vendor_payout_entries (batch_id);
-- FK-index discipline from V6: every FK gets an index or joins degrade.
CREATE INDEX idx_vendor_payout_order         ON vendor_payout_entries (order_id);

-- Per-vendor commission override. NULL means "use the platform default from
-- config" — resolution is vendor-first, and the RESOLVED rate is snapshotted
-- onto each entry at write time, so changing either never rewrites history.
ALTER TABLE users
    ADD COLUMN commission_rate NUMERIC(5, 4)
        CONSTRAINT users_commission_rate_range
        CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1));
