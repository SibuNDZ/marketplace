-- Carts and orders can name a specific variant (V20 added the options; this
-- is what makes buying one actually work).
--
-- NULLABLE on purpose, and permanently so. Every existing cart line and order
-- line means "the product itself", which is exactly what it meant when it was
-- written. Backfilling a variant onto those rows would invent a choice the
-- shopper never made, and there is no correct value to invent: a product with
-- three options has no way of knowing which one a pre-variant order was for.
--
-- So null keeps meaning "no option, buy the product", forever, for products
-- that have no variants. It is not a migration state waiting to be cleaned up.

ALTER TABLE cart_items
    ADD COLUMN variant_id BIGINT NULL REFERENCES product_variants(id) ON DELETE CASCADE;

-- ON DELETE CASCADE on the CART: if a vendor deletes an option, a cart line
-- for it is meaningless and should disappear rather than silently fall back
-- to some other option at a different price.
ALTER TABLE order_items
    ADD COLUMN variant_id BIGINT NULL REFERENCES product_variants(id) ON DELETE SET NULL,
    ADD COLUMN variant_label_at_purchase VARCHAR(100) NULL;

-- ON DELETE SET NULL on the ORDER, the opposite choice, for the same reason
-- order_items.product_id is nullable: order history must outlive the catalogue.
-- The label is snapshotted alongside it so a receipt still reads
-- "Rose Gold Watch set — Large" after the option itself is gone, exactly as
-- product_name_at_purchase already does for the product.

-- A cart holds ONE line per (product, option). Without this, adding Small
-- and then Large either collapses into one line or silently duplicates,
-- depending on which code path runs.
--
-- COALESCE rather than a plain UNIQUE: Postgres treats NULLs as distinct in
-- unique constraints, so (cart, product, NULL) could be inserted twice and
-- the no-variant case — every row that exists today — would be unprotected.
-- NULLS NOT DISTINCT would also work but is Postgres 15+, and this index
-- needs no version assumption.
CREATE UNIQUE INDEX uq_cart_items_line
    ON cart_items (cart_id, product_id, COALESCE(variant_id, 0));

CREATE INDEX idx_cart_items_variant  ON cart_items (variant_id) WHERE variant_id IS NOT NULL;
CREATE INDEX idx_order_items_variant ON order_items (variant_id) WHERE variant_id IS NOT NULL;
