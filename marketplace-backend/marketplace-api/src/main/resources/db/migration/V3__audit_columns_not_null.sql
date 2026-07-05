-- V3__audit_columns_not_null.sql
-- Companion to the @EnableJpaAuditing fix (commit 96926d2).
--
-- CONTEXT: Only OrderStatusHistory extends BaseEntity (Spring Data @CreatedDate).
-- All other entities (User, Product, Order, Cart, Review) use Hibernate's own
-- @CreationTimestamp / @UpdateTimestamp, which were always active.
-- V1 created created_at as NOT NULL for those tables but left updated_at nullable.
-- This migration makes both columns NOT NULL, making the schema match the
-- application invariant: every persisted row has both timestamps.
--
-- Backfilling with now() acknowledges the time recorded is wrong for any rows
-- that genuinely have nulls in updated_at; there is no recoverable truth, and
-- NULL is worse for every consumer (sorting, display, drift detection). The
-- comment here is the difference between a hack and a documented decision.
--
-- Tables: every V1 table whose entity has @CreationTimestamp / @UpdateTimestamp
-- or extends BaseEntity. Excludes cart_items and order_items (no audit columns).
-- order_status_history is already NOT NULL from V2 — skip it.

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'users', 'products', 'orders', 'carts', 'reviews'
    ] LOOP
        EXECUTE format('UPDATE %I SET created_at = now() WHERE created_at IS NULL', t);
        EXECUTE format('UPDATE %I SET updated_at = now() WHERE updated_at IS NULL', t);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN created_at SET NOT NULL', t);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN updated_at SET NOT NULL', t);
    END LOOP;
END $$;
