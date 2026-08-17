-- Search could not find a product by the name of the category it is filed in.
--
-- Reported case: the catalogue contains "Jimmy Choo Pumps", filed under a
-- category literally called "Shoes". Searching "shoes" returned NOTHING —
-- the name says Pumps, the description says pumps, the tags are empty, and
-- V21's vector covers only name, tags and description. The one word a
-- shopper is most likely to type was the one word search could not see.
--
-- Synonyms alone do not fix this class. They fix the instance: add
-- shoes->pumps and this query works, until someone adds a Bags category, or
-- Candles, and each new category arrives with the same hole on day one. A
-- category name is already a curated, human-written description of what the
-- thing IS; not indexing it was the actual bug.

-- ---------------------------------------------------------------------------
-- 1. One definition of the vector, called from everywhere
-- ---------------------------------------------------------------------------
-- V21 wrote the same expression in two places (the trigger and the backfill)
-- and noted the duplication was deliberate. Adding a third copy for the
-- category-rename reindex below is where that stops being reasonable, so it
-- becomes a function and the three callers share it.
--
-- STABLE, not IMMUTABLE: array_to_string() is only STABLE, which is the same
-- constraint that forced V21 to use a trigger rather than a generated column.
CREATE OR REPLACE FUNCTION product_search_vector(
    p_name        text,
    p_tags        text[],
    p_description text,
    p_category    text
) RETURNS tsvector AS $$
    SELECT setweight(to_tsvector('english', coalesce(p_name, '')), 'A')
        || setweight(to_tsvector('english', coalesce(array_to_string(p_tags, ' '), '')), 'B')
        -- Category shares weight B with tags: both are deliberate
        -- classifications of what the item is, unlike prose. It sits below
        -- the product's own name, so "Shoes" the category never outranks a
        -- product actually named for the query.
        || setweight(to_tsvector('english', coalesce(p_category, '')), 'B')
        || setweight(to_tsvector('english', coalesce(p_description, '')), 'C');
$$ LANGUAGE sql STABLE;

-- Direct category only, NOT the parent. "Fashion" as a parent would put every
-- garment and every shoe behind one very broad word, which turns a search
-- into a catalogue dump — the same failure V21 kept the synonym list small to
-- avoid. Revisit if shoppers actually search top-level names.

-- ---------------------------------------------------------------------------
-- 2. Products trigger: now category-aware
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION products_search_vector_update() RETURNS trigger AS $$
DECLARE
    v_category text;
BEGIN
    SELECT c.name INTO v_category FROM categories c WHERE c.id = NEW.category_id;
    NEW.search_vector := product_search_vector(
        NEW.name, NEW.tags, NEW.description, v_category);
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- category_id added to the column list. Moving a product between categories
-- changes what it is findable by, so it has to re-index; V21's list did not
-- include it because the category was not in the vector.
DROP TRIGGER IF EXISTS trg_products_search_vector ON products;
CREATE TRIGGER trg_products_search_vector
    BEFORE INSERT OR UPDATE OF name, description, tags, category_id ON products
    FOR EACH ROW EXECUTE FUNCTION products_search_vector_update();

-- ---------------------------------------------------------------------------
-- 3. Renaming a category re-indexes its products
-- ---------------------------------------------------------------------------
-- Without this, renaming "Shoes" to "Footwear" leaves every product in it
-- still indexed under the old word and findable by nothing new — a silent
-- staleness bug that would surface as "search randomly stopped working for
-- that section" months later. Rare event, cheap trigger, no guessing later.
CREATE OR REPLACE FUNCTION categories_reindex_products() RETURNS trigger AS $$
BEGIN
    UPDATE products p
    SET search_vector = product_search_vector(p.name, p.tags, p.description, NEW.name)
    WHERE p.category_id = NEW.id;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- Updating search_vector alone does NOT re-fire the products trigger above:
-- that one is scoped to OF (name, description, tags, category_id), so there
-- is no recursion here.
CREATE TRIGGER trg_categories_reindex_products
    AFTER UPDATE OF name ON categories
    FOR EACH ROW WHEN (OLD.name IS DISTINCT FROM NEW.name)
    EXECUTE FUNCTION categories_reindex_products();

-- ---------------------------------------------------------------------------
-- 4. Re-index everything that already exists
-- ---------------------------------------------------------------------------
UPDATE products p
SET search_vector = product_search_vector(
        p.name, p.tags, p.description, (SELECT c.name FROM categories c WHERE c.id = p.category_id));

-- ---------------------------------------------------------------------------
-- 5. Footwear synonyms
-- ---------------------------------------------------------------------------
-- Still worth adding alongside the structural fix: the category now makes
-- "shoes" find anything filed under Shoes, but "heels", "pumps" and "takkies"
-- are not category names and never will be. V21 seeded takkies/sneakers and
-- then stopped, so the most obvious English word for the category had no
-- route in at all.
--
-- ON CONFLICT DO NOTHING because uq_search_synonyms_pair already holds the
-- sneakers/takkies/trainers rows from V21 — re-inserting them must not fail
-- the migration.
INSERT INTO search_synonyms (term, synonym) VALUES
    ('shoes', 'footwear'), ('footwear', 'shoes'),
    ('shoes', 'pumps'),    ('pumps', 'shoes'),
    ('shoes', 'heels'),    ('heels', 'shoes'),
    ('shoes', 'sneakers'), ('sneakers', 'shoes'),
    ('shoes', 'takkies'),  ('takkies', 'shoes'),
    ('shoes', 'boots'),    ('boots', 'shoes'),
    ('shoes', 'sandals'),  ('sandals', 'shoes'),
    ('heels', 'stilettos'),('stilettos', 'heels'),
    ('pumps', 'heels'),    ('heels', 'pumps'),
    ('slops', 'sandals'),  ('flipflops', 'sandals')
ON CONFLICT (term, synonym) DO NOTHING;
