-- Product variants: colour / size / pack options (see product-variants.md).
--
-- Step 1 of 3, and deliberately INERT: this table exists and vendors can
-- populate it, but nothing in the buy path reads it yet. The cart, order and
-- stock-decrement changes land in step 2, and the buyer-facing selector last
-- of all — a selector that does not actually determine what gets bought
-- would be a lie to the buyer.
--
-- ONE FLAT AXIS. `label` is a single choice ("Black", "XL", "2pcs black"),
-- not colour x size as a matrix. A matrix multiplies into an editor that
-- market vendors will not fill in; a vendor who genuinely needs both enters
-- "Black / XL" as one label. Revisit only when a real vendor asks.
--
-- ABSOLUTE price, not a delta off the product. A delta looks tidy until the
-- vendor discounts the base product and every variant silently moves with
-- it. The buyer is shown an absolute number, so store the absolute number.

CREATE TABLE product_variants (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    label      VARCHAR(100) NOT NULL,
    sku        VARCHAR(100),
    price      NUMERIC(10, 2) NOT NULL
        CONSTRAINT product_variants_price_positive CHECK (price > 0),
    -- Named stock_quantity to match products; the column the order path will
    -- decrement in step 2. The CHECK is the last line of defence behind the
    -- pessimistic lock, not a substitute for it.
    stock_quantity INTEGER NOT NULL DEFAULT 0
        CONSTRAINT product_variants_stock_non_negative CHECK (stock_quantity >= 0),
    image_key  VARCHAR(500),
    position   INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP,
    -- A product cannot offer the same choice twice: two "Black" rows would
    -- be an unresolvable pick for the buyer and an ambiguous stock target.
    CONSTRAINT product_variants_label_unique UNIQUE (product_id, label)
);

CREATE INDEX idx_product_variants_product ON product_variants (product_id, position);
