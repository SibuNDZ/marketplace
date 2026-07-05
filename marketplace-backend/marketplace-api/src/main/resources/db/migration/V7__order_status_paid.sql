-- V7__order_status_paid.sql
-- Adding PAID to the order lifecycle: PENDING -> PAID -> SHIPPED.
--
-- The enum is @Enumerated(STRING) so the COLUMN needs nothing — but the
-- CONSTRAINT does: Hibernate 6.2+ (Boot 3.2 ships 6.3) generates a CHECK
-- constraint listing the enum values for enumerated columns, and the V1
-- baseline captured it. Without this migration, the first PAID write dies
-- with a check violation — at webhook time, in production, on a real
-- payment. This is THE trap of adding enum values under Hibernate 6.
--
-- ADAPTATION: constraint name confirmed from V1__baseline.sql — it is
-- orders_status_check (Hibernate convention: <table>_<column>_check).
--
-- CONFIRMED and PROCESSING are removed from the constraint: they were
-- placeholder states never wired into the transition graph and are now
-- removed from the OrderStatus enum as well. No existing data uses them.

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;

ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED',
                      'CANCELLED', 'REFUNDED'));
