-- Multiple photos per product, replacing the single products.image_key.
--
-- ONE source of truth, deliberately. The obvious cheaper move is to keep
-- image_key as "the main photo" and add a table for the extra ones, but then
-- two places both claim to hold the product's picture and every reader has to
-- know which wins. This table is the only place a product photo lives, and
-- ProductResponse.imageUrl becomes a derived convenience meaning "the first
-- one" so every existing consumer — cards, cart rows, rails — keeps working
-- untouched.
--
-- This repo already carries the cost of not doing that once: products.image_url
-- from V1 has been dead since V11 replaced it with image_key, and it is still
-- sitting there. Adding a second abandoned column would repeat that.
CREATE TABLE product_images (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    image_key   VARCHAR(500) NOT NULL,
    -- 0-based display order. No UNIQUE (product_id, position): appending and
    -- deleting would then need a temporary value to shuffle around, and the
    -- ordering is served by (position, id) which is total regardless of
    -- duplicates. Ties break on insertion order, which is the intuitive
    -- answer anyway.
    position    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- The only access pattern: every image for one product, in order.
CREATE INDEX idx_product_images_product ON product_images (product_id, position, id);

-- Carry the existing photos over as position 0. Every product that has a
-- picture today keeps exactly that picture as its first one, so nothing
-- visibly changes on deploy.
INSERT INTO product_images (product_id, image_key, position, created_at)
SELECT id, image_key, 0, now()
FROM products
WHERE image_key IS NOT NULL;

-- Now that the data is copied, the old column goes. Dropped in the same
-- migration as the backfill rather than left for a later cleanup, because a
-- column nothing reads is exactly what image_url became.
--
-- Deploy note: Flyway runs before the app accepts traffic, so the new code
-- and this schema arrive together. A previous instance still serving during
-- a rolling replace would fail on reads for the seconds it survives; that is
-- acceptable here and worth naming rather than pretending the window is zero.
ALTER TABLE products DROP COLUMN image_key;
