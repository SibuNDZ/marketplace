-- Full-text search over products, replacing LIKE '%term%' on the name.
--
-- The old search matched a raw substring against products.name only. On a
-- catalogue this small that fails constantly in one specific way: a shopper
-- searches a word the vendor did not put in the title ("perfume" when the
-- listing says "fragrance", "watch" when it says "timepiece") and gets an
-- empty page, even though the right product is sitting there. Zero results
-- is the failure mode that matters here, not ranking quality.
--
-- Three changes fix that:
--   1. Search the description and tags too, not just the name.
--   2. Stem, so "watches" matches "watch" and "necklaces" matches "necklace".
--   3. Expand the query through a curated synonym table (below), so words
--      the vendor never typed can still find the product.

-- ---------------------------------------------------------------------------
-- 1. The search vector
-- ---------------------------------------------------------------------------
-- A TRIGGER, not a GENERATED column, and that is forced rather than chosen:
-- a stored generated column requires an IMMUTABLE expression, and
-- array_to_string() is only STABLE (pg_proc.provolatile = 's'), so the
-- generated form is rejected outright by Postgres. tags is text[], so any
-- expression covering it has to go through array_to_string. Verified against
-- PostgreSQL 16 before writing this.
--
-- Weights follow the usual A/B/C convention and matter for ranking:
--   A = name        (what the thing IS)
--   B = tags        (vendor's own keywords, deliberately above description:
--                    they are chosen as search terms, prose is not)
--   C = description (supporting text, lots of words, weakest signal)
ALTER TABLE products ADD COLUMN search_vector tsvector;

CREATE OR REPLACE FUNCTION products_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
           setweight(to_tsvector('english', coalesce(NEW.name, '')), 'A')
        || setweight(to_tsvector('english', coalesce(array_to_string(NEW.tags, ' '), '')), 'B')
        || setweight(to_tsvector('english', coalesce(NEW.description, '')), 'C');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- OF (name, description, tags): an UPDATE that only touches stock or price
-- should not pay to rebuild the vector.
CREATE TRIGGER trg_products_search_vector
    BEFORE INSERT OR UPDATE OF name, description, tags ON products
    FOR EACH ROW EXECUTE FUNCTION products_search_vector_update();

-- Backfill existing rows. Written out rather than a no-op UPDATE that leans
-- on the trigger, so the intent survives if the trigger is ever changed.
UPDATE products
SET search_vector =
       setweight(to_tsvector('english', coalesce(name, '')), 'A')
    || setweight(to_tsvector('english', coalesce(array_to_string(tags, ' '), '')), 'B')
    || setweight(to_tsvector('english', coalesce(description, '')), 'C');

CREATE INDEX idx_products_search_vector ON products USING GIN (search_vector);

-- ---------------------------------------------------------------------------
-- 2. Synonyms
-- ---------------------------------------------------------------------------
-- A TABLE, not a Postgres thesaurus dictionary. A real thesaurus needs a
-- config file inside the server's share/tsearch_data directory, which is not
-- reachable on managed Postgres (Railway), and would make the search
-- behaviour undeployable from a migration. A table is queryable, editable
-- from the admin side later, and travels with the schema.
--
-- One row per direction. "perfume -> fragrance" does NOT imply
-- "fragrance -> perfume"; both are seeded where both make sense, which keeps
-- the semantics obvious instead of hiding a symmetry rule in code.
CREATE TABLE search_synonyms (
    id         BIGSERIAL PRIMARY KEY,
    term       VARCHAR(64) NOT NULL,
    synonym    VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_search_synonyms_pair UNIQUE (term, synonym),
    -- Lower-case only: lookups normalise the query before hitting this table,
    -- so a capitalised row here would simply never match anything.
    CONSTRAINT ck_search_synonyms_lower CHECK (
        term = LOWER(term) AND synonym = LOWER(synonym))
);

CREATE INDEX idx_search_synonyms_term ON search_synonyms (term);

-- Seeded against what this catalogue actually sells (jewellery and watches,
-- fragrance and skincare, pantry goods, home textiles and furniture) plus
-- South African vocabulary a local shopper is likely to type. Kept small and
-- specific on purpose: a bloated synonym list turns every search into a
-- catalogue dump, which on 12 products is worse than a near miss.
INSERT INTO search_synonyms (term, synonym) VALUES
    -- watches and jewellery
    ('watch', 'timepiece'), ('watch', 'wristwatch'), ('timepiece', 'watch'),
    ('wristwatch', 'watch'),
    ('jewellery', 'jewelry'), ('jewelry', 'jewellery'),
    ('jewellery', 'necklace'), ('jewellery', 'earrings'), ('jewellery', 'bracelet'),
    ('necklace', 'chain'), ('chain', 'necklace'), ('necklace', 'pendant'),
    ('earrings', 'studs'), ('studs', 'earrings'),
    ('ring', 'band'),
    -- fragrance and beauty
    ('perfume', 'fragrance'), ('fragrance', 'perfume'), ('perfume', 'scent'),
    ('scent', 'fragrance'), ('cologne', 'fragrance'), ('eau', 'fragrance'),
    ('lotion', 'moisturiser'), ('moisturiser', 'lotion'), ('moisturizer', 'lotion'),
    ('cream', 'moisturiser'), ('skincare', 'skin'), ('makeup', 'cosmetics'),
    ('cosmetics', 'makeup'), ('lipstick', 'lip'),
    -- pantry, with the South African staples spelled how people search them
    ('tea', 'rooibos'), ('rooibos', 'tea'), ('redbush', 'rooibos'),
    ('biltong', 'droewors'), ('jerky', 'biltong'), ('biltong', 'jerky'),
    ('honey', 'heuning'), ('heuning', 'honey'),
    ('braai', 'barbecue'), ('barbecue', 'braai'), ('bbq', 'braai'),
    ('spice', 'seasoning'), ('seasoning', 'spice'), ('masala', 'spice'),
    -- home and living
    ('rug', 'mat'), ('mat', 'rug'), ('carpet', 'rug'),
    ('couch', 'sofa'), ('sofa', 'couch'), ('lounge', 'sofa'),
    ('drawers', 'pedestal'), ('pedestal', 'drawers'), ('cupboard', 'cabinet'),
    ('cabinet', 'cupboard'), ('decor', 'ornament'),
    ('blanket', 'throw'), ('duvet', 'bedding'), ('bedding', 'linen'),
    -- clothing, including the SA term for trainers
    ('takkies', 'sneakers'), ('sneakers', 'takkies'), ('trainers', 'sneakers'),
    ('jersey', 'jumper'), ('jumper', 'jersey'), ('jersey', 'sweater'),
    ('beanie', 'hat'), ('hat', 'beanie'), ('scarf', 'shawl'),
    ('dress', 'gown'), ('gown', 'dress'),
    -- generic intent words shoppers actually type
    ('handmade', 'handcrafted'), ('handcrafted', 'handmade'),
    ('gift', 'present'), ('present', 'gift');
