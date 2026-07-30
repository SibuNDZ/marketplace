-- V15__bash_categories_expansion.sql
-- Adds a richer category taxonomy inspired by fashion-marketplace conventions.
--
-- Five new top-level categories are introduced: Clothing, Shoes (slug: footwear
-- to avoid conflict with the existing fashion > shoes subcategory), Accessories,
-- Jewellery (slug: jewellery-collections to avoid conflict with fashion > jewellery),
-- and Sport. Existing categories and their slugs are untouched.
--
-- A missing "Tools & brushes" subcategory is added under the existing
-- "Beauty and personal care" root.

-- ============================================================
-- 1. New top-level categories
-- ============================================================

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order) VALUES
    (NULL, 'Clothing',    'clothing',               '👕', 32),
    -- slug 'shoes' is taken by fashion > shoes; use 'footwear' for this root
    (NULL, 'Shoes',       'footwear',               '👟', 33),
    (NULL, 'Accessories', 'accessories',             '👜', 34),
    -- slug 'jewellery' is taken by fashion > jewellery subcategory
    (NULL, 'Jewellery',   'jewellery-collections',  '💍', 36),
    (NULL, 'Sport',       'sport',                  '🏃', 37);

-- ============================================================
-- 2. Clothing subcategories  (from bash.com Women/Men > Clothing)
-- ============================================================

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order)
SELECT p.id, c.name, c.slug, NULL, c.sort_order
FROM (VALUES
    ('clothing', 'Tops & T-shirts',   'tops-and-t-shirts',   10),
    ('clothing', 'Knitwear',          'knitwear',            20),
    ('clothing', 'Plus size',         'plus-size',           30),
    ('clothing', 'Jeans',             'jeans',               40),
    ('clothing', 'Hoodies & sweats',  'hoodies-and-sweats',  50),
    ('clothing', 'Pants & leggings',  'pants-and-leggings',  60),
    ('clothing', 'Dresses',           'dresses',             70),
    ('clothing', 'Sleepwear',         'sleepwear',           80),
    ('clothing', 'Shirts',            'shirts',              90),
    ('clothing', 'Jackets',           'jackets',            100),
    ('clothing', 'Blazers',           'blazers',            110),
    ('clothing', 'Coats',             'coats',              120),
    ('clothing', 'Suits',             'suits',              130),
    ('clothing', 'Activewear',        'activewear',         140),
    ('clothing', 'Jumpsuits',         'jumpsuits',          150),
    ('clothing', 'Socks & hosiery',   'socks-and-hosiery',  160),
    ('clothing', 'Lingerie',          'lingerie',           170),
    ('clothing', 'Blouses',           'blouses',            180)
) AS c(parent_slug, name, slug, sort_order)
JOIN public.categories p ON p.slug = c.parent_slug AND p.parent_id IS NULL;

-- ============================================================
-- 3. Shoes subcategories  (from bash.com Women/Men > Shoes)
-- ============================================================

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order)
SELECT p.id, c.name, c.slug, NULL, c.sort_order
FROM (VALUES
    ('footwear', 'Boots',        'boots',        10),
    ('footwear', 'Flats',        'flats',        20),
    ('footwear', 'Heels',        'heels',        30),
    ('footwear', 'Wedges',       'wedges',       40),
    ('footwear', 'Sandals',      'sandals',      50),
    ('footwear', 'Slides',       'slides',       60),
    ('footwear', 'Sneakers',     'sneakers',     70),
    ('footwear', 'Sports shoes', 'sports-shoes', 80),
    ('footwear', 'Flip flops',   'flip-flops',   90)
) AS c(parent_slug, name, slug, sort_order)
JOIN public.categories p ON p.slug = c.parent_slug AND p.parent_id IS NULL;

-- ============================================================
-- 4. Accessories subcategories  (from bash.com Women/Men > Accessories)
-- ============================================================
-- 'bags' is already used by fashion > bags subcategory; use 'handbags' here.

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order)
SELECT p.id, c.name, c.slug, NULL, c.sort_order
FROM (VALUES
    ('accessories', 'Handbags',            'handbags',           10),
    ('accessories', 'Purses',              'purses',             20),
    ('accessories', 'Belts',               'belts',              30),
    ('accessories', 'Hair accessories',    'hair-accessories',   40),
    ('accessories', 'Headwear',            'headwear',           50),
    ('accessories', 'Scarves & gloves',    'scarves-and-gloves', 60)
) AS c(parent_slug, name, slug, sort_order)
JOIN public.categories p ON p.slug = c.parent_slug AND p.parent_id IS NULL;

-- ============================================================
-- 5. Jewellery subcategories  (from bash.com Women/Men > Jewellery)
-- ============================================================

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order)
SELECT p.id, c.name, c.slug, NULL, c.sort_order
FROM (VALUES
    ('jewellery-collections', 'Fashion jewellery',    'fashion-jewellery',    10),
    ('jewellery-collections', 'Fine jewellery',       'fine-jewellery',       20),
    ('jewellery-collections', 'Watches',              'watches',              30),
    ('jewellery-collections', 'Bracelets & bangles',  'bracelets-and-bangles',40),
    ('jewellery-collections', 'Earrings',             'earrings',             50),
    ('jewellery-collections', 'Necklaces',            'necklaces',            60),
    ('jewellery-collections', 'Pendants',             'pendants',             70),
    ('jewellery-collections', 'Rings',                'rings',                80)
) AS c(parent_slug, name, slug, sort_order)
JOIN public.categories p ON p.slug = c.parent_slug AND p.parent_id IS NULL;

-- ============================================================
-- 6. Sport subcategories  (from bash.com Womens Sport)
-- ============================================================

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order)
SELECT p.id, c.name, c.slug, NULL, c.sort_order
FROM (VALUES
    ('sport', 'Sport clothing', 'sport-clothing', 10),
    ('sport', 'Sport shoes',    'sport-shoes',    20)
) AS c(parent_slug, name, slug, sort_order)
JOIN public.categories p ON p.slug = c.parent_slug AND p.parent_id IS NULL;

-- ============================================================
-- 7. Missing Beauty subcategory
-- ============================================================

INSERT INTO public.categories (parent_id, name, slug, icon, sort_order)
SELECT p.id, 'Tools & brushes', 'tools-and-brushes', NULL, 60
FROM public.categories p
WHERE p.slug = 'beauty-and-personal-care' AND p.parent_id IS NULL;
