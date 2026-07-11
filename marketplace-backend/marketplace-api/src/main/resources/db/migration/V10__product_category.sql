-- V10__product_category.sql
-- Products get a real category column, replacing the frontend's
-- id-arithmetic category fabrication (marketplaceSignals.ts).
--
-- Same trap as V7 (order status): @Enumerated(STRING) means the COLUMN
-- needs no type work, but a CHECK constraint enumerating the values is
-- added here by hand rather than left to Hibernate, because this is a
-- brand-new column, not a baseline-captured one. If ProductCategory ever
-- gains a value, the enum AND a follow-up migration updating this CHECK
-- must change together — exactly the V7 lesson, restated for a new column.
--
-- Five categories, not the seventeen in the pre-this-slice frontend
-- taxonomy: this is a food-and-crafts local marketplace, and Vehicles /
-- Property Rentals / Electronics never had — and were never going to
-- have — real products behind them. OTHER is the honest catch-all.

ALTER TABLE products ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER';

ALTER TABLE products ADD CONSTRAINT products_category_check
    CHECK (category IN ('PRODUCE', 'PANTRY', 'CRAFTS', 'HOME', 'OTHER'));

-- Category browsing (?category=) is a hot catalog-read path, same shape
-- as idx_products_live from V8.
CREATE INDEX idx_products_category_live ON products (category)
    WHERE deleted_at IS NULL;
