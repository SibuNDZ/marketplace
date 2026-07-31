-- Task 2.3: per-vendor flat delivery fees.
--
-- users.delivery_fee is the vendor's CURRENT fee (meaningful only for
-- VENDOR rows; 0 for everyone, meaning "free delivery / not set").
--
-- order_delivery_fees snapshots the fee per (order, vendor) at placement
-- time, exactly as order_items snapshots prices: a vendor editing their fee
-- must never change what an existing order costs. vendor_id is nullable for
-- the same reason order_items.product_id is — history must survive the
-- referenced row's deletion; vendor_name_at_purchase carries the display.

ALTER TABLE users
    ADD COLUMN delivery_fee NUMERIC(10, 2) NOT NULL DEFAULT 0
        CONSTRAINT users_delivery_fee_non_negative CHECK (delivery_fee >= 0);

CREATE TABLE order_delivery_fees (
    id                       BIGSERIAL PRIMARY KEY,
    order_id                 BIGINT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    vendor_id                BIGINT REFERENCES users (id) ON DELETE SET NULL,
    vendor_name_at_purchase  VARCHAR(255) NOT NULL,
    fee_at_purchase          NUMERIC(10, 2) NOT NULL
        CONSTRAINT order_delivery_fees_non_negative CHECK (fee_at_purchase >= 0),
    -- One delivery line per vendor per order is the checkout invariant;
    -- the constraint makes a double-insert a loud failure, not a double
    -- charge.
    CONSTRAINT order_delivery_fees_one_per_vendor UNIQUE (order_id, vendor_id)
);

CREATE INDEX idx_order_delivery_fees_order ON order_delivery_fees (order_id);
