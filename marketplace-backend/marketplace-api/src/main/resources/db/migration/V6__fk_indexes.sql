-- V6__fk_indexes.sql
-- PostgreSQL does NOT auto-index foreign key columns (it indexes the
-- referenced side via the PK, not the referencing side). Hibernate's
-- generated baseline created the FKs without indexes, so every
-- "my orders" page, review listing, and hasDeliveredPurchase gate has
-- been sequential-scanning — invisible at dev volumes, a cliff in prod.
--
-- IF NOT EXISTS everywhere: harmless if some already exist, and this
-- migration stays re-derivable from the rule "every FK gets an index".
--
-- ADAPTATION: confirm column names against V1 (user_id vs customer_id
-- was already settled as user_id in V4 — these follow suit).

CREATE INDEX IF NOT EXISTS idx_orders_user            ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order      ON order_items (order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product    ON order_items (product_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_cart        ON cart_items (cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_product     ON cart_items (product_id);
CREATE INDEX IF NOT EXISTS idx_reviews_product        ON reviews (product_id);
CREATE INDEX IF NOT EXISTS idx_products_vendor        ON products (vendor_id);
-- reviews.user_id is the leading column of uq_reviews_user_product (V4),
-- so it's already indexed. carts.user_id likewise has a unique constraint.
-- order_status_history.order_id is covered by V2's composite index.

-- The one non-FK index that matters for correctness-adjacent queries:
-- the expiry job (V7 slice) scans for stale PENDING orders.
CREATE INDEX IF NOT EXISTS idx_orders_status_created
    ON orders (status, created_at);
