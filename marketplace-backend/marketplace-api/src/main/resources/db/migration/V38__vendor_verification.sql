-- V38__vendor_verification.sql
-- Vendor verification, slice 1 (paystack-subaccounts.md §5). Numbered after
-- V37 (order_payment_reference), which merged from feat/paystack-provider on
-- 2026-10-18; Flyway has out-of-order off, so check main before renumbering.
--
-- One row per verification attempt; the LATEST row is the vendor's state.
-- Rows are never updated in place except for the three outcome columns
-- (outcome, verified_by, verified_at) and the admin note.
--
-- What is deliberately NOT here: a column for the identity document number.
-- document_last4 is the only part of the number that is ever stored (§5.3);
-- the number itself passes through one request and is dropped. None may be
-- added to this table.
--
-- provider_result gains NOT_RUN beyond the spec's three values: it is the
-- state this slice writes, because the Paystack validate call is not built
-- yet. CHECK constraints mirror the enum+CHECK lockstep of V27
-- (vendor_payout_entries_status_check): the Java enum and this list must
-- change together.

CREATE TABLE vendor_verifications (
    id                   BIGSERIAL PRIMARY KEY,
    vendor_id            BIGINT NOT NULL REFERENCES users (id),
    -- Nullable: the identity document is optional in this slice (the
    -- vendor may save banking without one); the Paystack slice makes it
    -- required when the validate call needs it.
    document_type        VARCHAR(32)
        CONSTRAINT vendor_verifications_document_type_check
        CHECK (document_type IS NULL OR document_type IN ('ID_NUMBER', 'PASSPORT', 'CIPC')),
    document_last4       VARCHAR(4),
    account_holder_name  VARCHAR(120) NOT NULL,
    bank_code            VARCHAR(16),
    account_last4        VARCHAR(4),
    account_type         VARCHAR(20),
    provider             VARCHAR(16) NOT NULL DEFAULT 'PAYSTACK',
    provider_result      VARCHAR(16) NOT NULL
        CONSTRAINT vendor_verifications_provider_result_check
        CHECK (provider_result IN ('OK', 'MISMATCH', 'UNAVAILABLE', 'NOT_RUN')),
    account_holder_match BOOLEAN,
    account_open         BOOLEAN,
    account_open_3m      BOOLEAN,
    accepts_credits      BOOLEAN,
    verification_message VARCHAR(255),
    outcome              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT vendor_verifications_outcome_check
        CHECK (outcome IN ('PENDING', 'APPROVED', 'REJECTED')),
    verified_by          BIGINT REFERENCES users (id),
    verified_at          TIMESTAMP,
    note                 TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT now()
);

-- "Latest row per vendor" is the only read pattern; the admin pending list
-- walks it too.
CREATE INDEX idx_vendor_verifications_vendor_created
    ON vendor_verifications (vendor_id, created_at DESC);
-- FK index discipline from V6.
CREATE INDEX idx_vendor_verifications_verified_by
    ON vendor_verifications (verified_by);

-- users: the bank code the provider keys on (§5.2), when the vendor was
-- last approved (NULL = not sellable once the gate is on), and the Paystack
-- subaccount code, which stays NULL until the Paystack slice writes it.
-- All nullable, no backfill: every live vendor goes through re-approval
-- (§4.5) rather than being grandfathered by a migration.
ALTER TABLE users
    ADD COLUMN bank_code                VARCHAR(16),
    ADD COLUMN vendor_approved_at       TIMESTAMP,
    ADD COLUMN paystack_subaccount_code VARCHAR(40);

-- Which rail settled a ledger entry (§6.2): EFT for the historic
-- collect-then-pay path (every existing row), SPLIT once Paystack settles
-- the vendor's share at the transaction.
ALTER TABLE vendor_payout_entries
    ADD COLUMN settlement_mode VARCHAR(8) NOT NULL DEFAULT 'EFT'
        CONSTRAINT vendor_payout_entries_settlement_mode_check
        CHECK (settlement_mode IN ('EFT', 'SPLIT'));
