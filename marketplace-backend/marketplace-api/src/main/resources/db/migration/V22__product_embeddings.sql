-- Semantic embeddings for related-products (V21 gave us lexical similarity).
--
-- Text similarity can only relate products that share WORDS. It cannot tell
-- that a fragrance gift set and a body lotion are alike, and it actively
-- mismatches on words that carry no meaning — the "gift set" / "watch set"
-- bug. Embeddings compare MEANING, which is the right tool, and crucially
-- they need no purchase history: the vector comes from the product's own
-- text, so this works on a catalogue with zero sales.
--
-- Deliberately NOT pgvector, for now. The extension is available on this
-- server (0.8.4) but its value is the approximate-nearest-neighbour index,
-- and at this catalogue size every similarity is computed by comparing a
-- handful of vectors — an index over 12 rows earns nothing and the planner
-- would ignore it anyway. A plain array keeps the schema portable and skips
-- an extension install. Revisit when the catalogue reaches a few thousand
-- products or when query-time semantic SEARCH (not just precomputed
-- similarity) is wanted; the migration to a vector column is mechanical.
ALTER TABLE products
    ADD COLUMN embedding      DOUBLE PRECISION[],
    ADD COLUMN embedding_hash VARCHAR(64),
    ADD COLUMN embedded_at    TIMESTAMP;

-- embedding_hash is a SHA-256 of the exact text that was embedded, not a
-- timestamp comparison. A vendor who re-saves a product without changing its
-- name, tags or description must NOT trigger a paid re-embed, and a vendor
-- who edits one word must. The hash is the only thing that answers both.
COMMENT ON COLUMN products.embedding_hash IS
    'SHA-256 of the embedded text. Re-embed only when this changes.';

-- Partial index on the backfill predicate: the job asks "which products have
-- no embedding, or one whose hash no longer matches" on a schedule, forever.
-- Trivial today, correct when the catalogue is large.
CREATE INDEX idx_products_embedding_missing
    ON products (id) WHERE embedding IS NULL AND deleted_at IS NULL;
