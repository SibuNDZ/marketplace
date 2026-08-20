-- V28__payout_batches_and_banking.sql
-- Payout operations: batches (approve -> export -> mark paid) and the vendor
-- banking details the bank file is built from.
--
-- Banking lands HERE, one phase earlier than the task spec's ordering,
-- because the bulk-payment exporter cannot exist without an account number
-- to export. The terms-acceptance fields and the selling gate stay in the
-- next slice; this one only makes the columns exist.

CREATE TABLE vendor_payout_batches (
    id                 BIGSERIAL PRIMARY KEY,
    -- Audit trail: WHO moved money matters more here than anywhere else in
    -- the schema. approved_by is NOT NULL because a batch cannot exist
    -- without someone having approved it; paid_by fills at mark-paid time.
    approved_by        BIGINT NOT NULL REFERENCES users (id),
    approved_at        TIMESTAMP NOT NULL DEFAULT now(),
    exported_at        TIMESTAMP,
    paid_by            BIGINT REFERENCES users (id),
    paid_at            TIMESTAMP,
    payment_reference  VARCHAR(100),
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP
);

-- Batch lifecycle is DERIVED, not a status column: approved_at always set,
-- then exported_at, then paid_at. Three timestamps cannot disagree with a
-- fourth column about what happened.

CREATE INDEX idx_vendor_payout_batches_approved_by ON vendor_payout_batches (approved_by);
CREATE INDEX idx_vendor_payout_batches_paid_by     ON vendor_payout_batches (paid_by);

-- batch_id was a bare column in V27 (the batches table did not exist yet);
-- now it gets its FK.
ALTER TABLE vendor_payout_entries
    ADD CONSTRAINT fk_vendor_payout_entries_batch
    FOREIGN KEY (batch_id) REFERENCES vendor_payout_batches (id);

-- ---------------------------------------------------------------------------
-- Vendor banking details, for the EFT bulk file.
-- ---------------------------------------------------------------------------
-- All nullable: completeness is a GATE (enforced in the application when the
-- gate ships), not a constraint — a NOT NULL here would break every existing
-- customer row and every vendor who has not onboarded to payouts yet.
--
-- These columns are sensitive. The application-side rules (masking to last 4
-- in every API response, never logging the entity) are the protection;
-- nothing in this schema should ever appear in a SELECT that feeds a log.
ALTER TABLE users
    ADD COLUMN account_holder_name VARCHAR(120),
    ADD COLUMN bank_name           VARCHAR(80),
    ADD COLUMN account_number      VARCHAR(20),
    ADD COLUMN branch_code         VARCHAR(10),
    -- Lockstep with the BankAccountType enum (the V7 discipline).
    ADD COLUMN account_type        VARCHAR(20)
        CONSTRAINT users_account_type_check
        CHECK (account_type IS NULL OR account_type IN ('CHEQUE', 'SAVINGS'));
