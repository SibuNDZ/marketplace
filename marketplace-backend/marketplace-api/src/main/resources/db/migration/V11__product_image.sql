-- V11__product_image.sql
-- One nullable column: the R2 object key (e.g. products/42/9f3a....webp).
-- Nullable is the design, not an oversight: existing products have no
-- image, and the frontend's fallback chain (real image -> placeholder)
-- is what makes that state presentable. The URL is NOT stored — it's
-- derived (public base + key) so the serving domain can change without
-- a data migration.

ALTER TABLE products ADD COLUMN image_key VARCHAR(255) NULL;
