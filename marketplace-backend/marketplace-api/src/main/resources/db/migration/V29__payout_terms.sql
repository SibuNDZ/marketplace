-- V29__payout_terms.sql
-- Payout terms acceptance, versioned.
--
-- The terms text is built from config (commission rate, payout window) and
-- versioned by app.payouts.terms-version. A vendor accepts a SPECIFIC
-- version; bumping the config version invalidates every prior acceptance,
-- which is what forces re-acceptance when the wording or the numbers change.
-- The accepted version is stored, not a boolean, for exactly that reason.
--
-- Both nullable: customers never accept payout terms, and existing vendors
-- start unaccepted (the selling gate, where enabled, is what makes that
-- matter).

ALTER TABLE users
    ADD COLUMN payout_terms_version     INTEGER,
    ADD COLUMN payout_terms_accepted_at TIMESTAMP;
