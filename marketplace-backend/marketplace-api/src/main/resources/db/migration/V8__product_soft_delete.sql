-- V8__product_soft_delete.sql
-- Products stop being hard-deleted. OrderItem.product FK and the review
-- purchase-gate JPQL both traverse product rows for historical orders —
-- a hard DELETE either fails on the FK or orphans that history. Soft
-- delete preserves the row; deleted_at NULL means live.
--
-- Filtering is explicit per-query in the repository (@SQLDelete/@Where
-- annotations are NOT used — they filter every query touching Product,
-- including OrderItem.getProduct() on delivered orders, causing
-- EntityNotFoundException on lazy resolution. See design comments).

ALTER TABLE products ADD COLUMN deleted_at TIMESTAMP NULL;

-- Partial index: catalog listing (WHERE deleted_at IS NULL) is the
-- hottest read path. Indexing only live rows keeps it small regardless
-- of delete volume.
CREATE INDEX idx_products_live ON products (created_at DESC)
    WHERE deleted_at IS NULL;

-- SKU uniqueness: a plain unique constraint would block a vendor from
-- relisting a deleted SKU — duplicate-key error on a product that
-- "doesn't exist". Partial uniqueness (live products only) fixes it.
--
-- ADAPTATION: V1 has no unique constraint on sku (no unique=true in
-- @Column annotation, no uk_ constraint in the baseline). The DROP is
-- a safe no-op; the CREATE UNIQUE INDEX ADDS uniqueness the schema
-- always implicitly wanted.
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_sku_key;

CREATE UNIQUE INDEX uq_products_sku_live ON products (sku)
    WHERE deleted_at IS NULL;
