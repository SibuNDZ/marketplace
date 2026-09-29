-- V33__vendor_referral_source.sql
-- Where a seller heard about eRestyu, asked once at the moment they become one.
--
-- There is no referral tracking in the platform and no analytics that can
-- attribute a signup to a campaign. Without this column, "did the seller
-- recruitment campaign work" is answerable only by guessing, which is the
-- same as not answering it. A self-reported answer is imperfect (people
-- misremember, and someone who saw a post and then searched will say
-- "search") but it is the honest floor: it is the only signal we actually
-- have, and it is stable enough to compare month on month.
--
-- NOT V32: V32 is claimed by the Paystack branch (orders.payment_reference).
-- Flyway runs with out-of-order disabled, so that branch must reach an
-- environment BEFORE this one or its V32 will be refused as out of order.
--
-- Nullable, and stays nullable forever:
--   - every customer row has no answer to give
--   - every vendor who registered before this shipped has none either, and
--     backfilling a guess would poison the only numbers this column exists
--     to produce. An unknown must read as unknown.
--
-- No CHECK constraint on referral_source. The values are a Java enum
-- (ReferralSource), so adding a channel is a code change; a CHECK would make
-- it a migration as well, and the set of marketing channels is exactly the
-- kind of thing that changes without warning. Hibernate validates the column
-- type, and the enum validates the value on the way in.
--
-- detail is free text and is only populated for OTHER. It is capped short on
-- purpose: it is a label, not a comment box, and it is displayed in an admin
-- table.

ALTER TABLE users
    ADD COLUMN referral_source        VARCHAR(40),
    ADD COLUMN referral_source_detail VARCHAR(120);

-- Answers are read as counts grouped by source, over vendors only. Partial
-- so the index stays small: the overwhelming majority of rows are customers
-- with a NULL source and are never part of that query.
CREATE INDEX idx_users_referral_source
    ON users (referral_source)
    WHERE referral_source IS NOT NULL;
