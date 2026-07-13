-- V12__add_shipping_address_to_orders.sql
-- Seven nullable columns, not a separate table: an order has exactly one
-- shipping address, captured once at pay-time and never edited afterward
-- (a changed address after payment is a support conversation, not a form
-- resubmit). If saved/reusable addresses become a real feature later,
-- that's a separate `addresses` table keyed to the user — this stays the
-- per-order snapshot, same philosophy as OrderItem's price snapshot.
--
-- The orders table already has unused shipping_address/billing_address
-- columns from V1 (confirmed dead — no code reads or writes them). Left
-- untouched here; this migration adds the structured replacement
-- alongside them rather than migrating/dropping the old ones, which is
-- out of scope for this slice.

ALTER TABLE orders ADD COLUMN recipient_name VARCHAR(200) NULL;
ALTER TABLE orders ADD COLUMN phone          VARCHAR(30)  NULL;
ALTER TABLE orders ADD COLUMN address_line1  VARCHAR(255) NULL;
ALTER TABLE orders ADD COLUMN address_line2  VARCHAR(255) NULL;
ALTER TABLE orders ADD COLUMN city           VARCHAR(100) NULL;
ALTER TABLE orders ADD COLUMN province       VARCHAR(100) NULL;
ALTER TABLE orders ADD COLUMN postal_code    VARCHAR(20)  NULL;

-- Nullable everywhere: orders 1-7 (pre-slice) have no address and must
-- keep working — the mapper treats null shipping as "not yet provided",
-- not an error state. New orders get NOT NULL enforced at the DTO
-- validation layer (@NotBlank), not the schema — the schema stays
-- permissive so a future field (e.g. delivery instructions) can be added
-- without another round of "is this nullable" migration archaeology.
