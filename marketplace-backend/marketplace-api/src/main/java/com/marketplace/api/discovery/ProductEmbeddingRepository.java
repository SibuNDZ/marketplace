package com.marketplace.api.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes product embedding vectors (V22).
 *
 * JdbcTemplate rather than JPA because the column is a
 * DOUBLE PRECISION[] — Hibernate needs a custom type or a converter to map a
 * Postgres array, and all that machinery would exist to serve three
 * statements. The array is also deliberately NOT on the Product entity: it is
 * a kilobyte of numbers per row that no other query wants loaded.
 */
@Repository
public class ProductEmbeddingRepository {

    private final JdbcTemplate jdbc;

    public ProductEmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One product awaiting embedding: its id and the text to embed. */
    public record Pending(long productId, String text) {}

    /**
     * Products whose stored vector is missing or stale.
     *
     * Staleness is a hash mismatch, not a timestamp: re-saving a product
     * without touching its text must not trigger a paid re-embed. The hash is
     * computed by the caller from the same text it is about to send, so the
     * two can never disagree.
     *
     * Soft-deleted products are skipped — paying to embed something no
     * shopper can see is pure waste.
     */
    public List<Pending> findStale(int limit) {
        // The hash is computed IN SQL, so the staleness test is exact and the
        // query returns only rows that genuinely need work. Doing it in Java
        // would mean fetching every product on every run and filtering after
        // the fact — the job would look busy and cost a full table read each
        // time. Postgres sha256() is built in, and encode(...,'hex') is
        // lower-case, matching the Java side byte for byte.
        return jdbc.query("""
                WITH candidate AS (
                    SELECT p.id,
                           p.name || ' ' || COALESCE(array_to_string(p.tags, ' '), '')
                                  || ' ' || COALESCE(p.description, '') AS text,
                           p.embedding,
                           p.embedding_hash
                    FROM products p
                    WHERE p.deleted_at IS NULL
                )
                SELECT id, text FROM candidate
                WHERE embedding IS NULL
                   OR embedding_hash IS DISTINCT FROM
                      encode(sha256(convert_to(text, 'UTF8')), 'hex')
                ORDER BY id
                LIMIT ?
                """,
                (rs, i) -> new Pending(rs.getLong("id"), rs.getString("text")),
                limit);
    }

    /**
     * @Transactional is REQUIRED here, not decoration.
     *
     * This datasource runs with hikari auto-commit: false. A JdbcTemplate
     * write outside a Spring-managed transaction therefore executes, reports
     * a row count, and is then silently discarded when the connection returns
     * to the pool — no exception, nothing persisted. Every JPA path in this
     * app is already inside a transaction, which is why nothing else hit it.
     *
     * That is exactly how this shipped broken once: the sweep logged
     * "Embedded 12 product(s)" while the table stayed empty, so the rows
     * looked stale forever and were re-embedded on every run — a silent,
     * recurring bill for work that was thrown away. The row-count check below
     * exists so a future failure is loud instead of invisible.
     */
    @org.springframework.transaction.annotation.Transactional
    public void save(long productId, String hash, double[] vector) {
        Double[] boxed = new Double[vector.length];
        for (int i = 0; i < vector.length; i++) boxed[i] = vector[i];

        int updated = jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE products
                    SET embedding = ?, embedding_hash = ?, embedded_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """);
            Array array = con.createArrayOf("float8", boxed);
            ps.setArray(1, array);
            ps.setString(2, hash);
            ps.setLong(3, productId);
            return ps;
        });

        if (updated != 1) {
            throw new IllegalStateException(
                    "Embedding write affected " + updated + " rows for product " + productId);
        }
    }

    /**
     * Every live, in-stock product that has a vector, as id -> vector.
     *
     * Loading the whole set and comparing in Java is the right shape at this
     * catalogue size and the wrong one at scale: it is O(catalogue) per
     * request. The guard is the caller's LIMIT plus the fact that a few
     * hundred 1024-float vectors is under a megabyte. Past a few thousand
     * products this becomes the moment to install pgvector and push the
     * nearest-neighbour search into the database, which is why the schema
     * comment in V22 says so.
     */
    public Map<Long, double[]> liveEmbeddings() {
        Map<Long, double[]> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, embedding FROM products
                WHERE deleted_at IS NULL AND stock_quantity > 0 AND embedding IS NOT NULL
                """, rs -> {
            Array array = rs.getArray("embedding");
            if (array == null) return;
            Double[] boxed = (Double[]) array.getArray();
            double[] vector = new double[boxed.length];
            for (int i = 0; i < boxed.length; i++) vector[i] = boxed[i] == null ? 0d : boxed[i];
            out.put(rs.getLong("id"), vector);
        });
        return out;
    }

    /** The vector for one product, or null when it has none yet. */
    public double[] embeddingOf(long productId) {
        List<double[]> rows = new ArrayList<>();
        jdbc.query("SELECT embedding FROM products WHERE id = ?", rs -> {
            Array array = rs.getArray("embedding");
            if (array == null) return;
            Double[] boxed = (Double[]) array.getArray();
            double[] vector = new double[boxed.length];
            for (int i = 0; i < boxed.length; i++) vector[i] = boxed[i] == null ? 0d : boxed[i];
            rows.add(vector);
        }, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
