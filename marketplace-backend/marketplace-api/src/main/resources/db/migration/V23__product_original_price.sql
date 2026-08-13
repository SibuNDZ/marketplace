-- Compare-at pricing: the "was" price a discount is measured against.
--
-- NULL means "not on sale", which is the state of every existing row and the
-- default for every new one. A product is only ever shown as discounted when
-- a vendor deliberately sets this, so nothing about this migration changes
-- how any current listing displays.
--
-- CHECK, not just validation in the service: a "was" price at or below the
-- selling price is not a discount, it is either a mistake or a lie. Rejecting
-- it in the database means no code path can write one, including a future
-- import script or a hand-run UPDATE.
--
-- The legal point is the reason this constraint is strict rather than
-- advisory. Section 30 of the Consumer Protection Act treats an advertised
-- former price as a representation that goods were actually offered at that
-- price; an invented one is a false representation. The schema cannot verify
-- that history, but it can refuse the shape that is obviously wrong.
ALTER TABLE products
    ADD COLUMN original_price NUMERIC(19, 2) NULL;

ALTER TABLE products
    ADD CONSTRAINT products_original_price_above_price
        CHECK (original_price IS NULL OR original_price > price);
