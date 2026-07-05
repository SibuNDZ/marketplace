-- V4__review_constraints.sql
-- The reviews table exists since V1. This migration adds the invariants the
-- review feature depends on.
--
-- 1) One review per (user, product). The service checks this in ReviewService
--    but the unique index is the backstop that makes the rule hold under
--    concurrent double-submit (double-tap on mobile is not hypothetical).
--    Same philosophy as stock locking: DB enforces invariants, service
--    provides good error messages.
--
-- 2) Rating bounds 1..5 are already enforced by V1's reviews_rating_check
--    constraint and the entity's @Min/@Max bean validation — no new CHECK
--    needed here, it would be a redundant duplicate.
--
-- Column names: user_id and product_id confirmed from V1__baseline.sql.
-- If dev data has duplicate (user_id, product_id) pairs, wipe the dev volume;
-- there is no production data, so no dedup logic belongs in this migration.

ALTER TABLE reviews
    ADD CONSTRAINT uq_reviews_user_product UNIQUE (user_id, product_id);
