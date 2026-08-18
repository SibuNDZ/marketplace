package com.marketplace.api.repository;

import com.marketplace.api.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // --- soft-delete aware catalog queries ---

    /** Live products only (deleted_at IS NULL). Used for public catalog. */
    Page<Product> findAllByDeletedAtIsNull(Pageable pageable);

    /**
     * Live catalogue with the optional ?category= and ?handmade= filters.
     *
     * ONE query with null-guarded predicates rather than four repository
     * methods for the combinations. The `:ids IS NULL` idiom lets an absent
     * filter drop out of the WHERE clause entirely, so the planner still
     * uses idx_products_category_id_live when a category IS supplied.
     *
     * categoryIds is a list, not a single id, because a top-level category
     * has to match its children too — CategoryService.resolveToIds does
     * that expansion.
     *
     * vendorId powers the public storefront (?vendorId=). It goes HERE
     * rather than reusing findByVendorId because that method deliberately
     * includes soft-deleted rows for the vendor's own dashboard and carries
     * a "never expose this on a public path" warning. Filtering through this
     * query keeps the deletedAt guard, and composes with category/search so
     * a shopper can narrow within one stall.
     */
    @Query("""
           SELECT p FROM Product p
           WHERE p.deletedAt IS NULL
             AND (:categoryIds IS NULL OR p.category.id IN :categoryIds)
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor.id = :vendorId)
                   AND (:searchDisabled = TRUE
                        OR LOWER(p.name) LIKE LOWER(CONCAT('%', :searchText, '%'))
                        OR LOWER(p.vendor.username) LIKE LOWER(CONCAT('%', :searchText, '%')))
           """)
    Page<Product> findFiltered(@Param("categoryIds") List<Long> categoryIds,
                               @Param("handmade") Boolean handmade,
                               @Param("searchDisabled") boolean searchDisabled,
                               @Param("searchText") String searchText,
                               @Param("vendorId") Long vendorId,
                               Pageable pageable);

    /**
     * Full-text search (V21), used ONLY when the caller supplied ?name=.
     *
     * Native rather than JPQL because tsvector/tsquery and ts_rank have no
     * JPQL equivalent, and deliberately a SEPARATE method from findFiltered
     * rather than another branch inside it: the browse path is the one every
     * category click goes through, and it is not worth risking a regression
     * there to save a method.
     *
     * tsQuery arrives pre-built and pre-sanitised from ProductService — it is
     * tsquery SYNTAX (lexemes joined by & and |), so it cannot be assembled
     * from raw user input here. See buildTsQuery for the sanitisation.
     *
     * The vendor ILIKE is an OR alongside the vector match so that searching
     * a stall name ("seorim") still finds that stall's products. Those rows
     * score 0 from ts_rank and therefore sort below genuine text matches,
     * which is the correct priority: a name match on the product beats a
     * match on who sells it.
     *
     * Ordering is rank first, then newest, so an unranked vendor match still
     * has a stable order rather than whatever the planner returns.
     */
    @Query(value = """
           SELECT p.* FROM products p
           LEFT JOIN users u ON u.id = p.vendor_id
           LEFT JOIN product_popularity pop ON pop.product_id = p.id
           WHERE p.deleted_at IS NULL
             AND (:categoryFilterDisabled = TRUE OR p.category_id IN (:categoryIds))
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor_id = :vendorId)
             AND (p.search_vector @@ to_tsquery('english', :tsQuery)
                  OR u.business_name ILIKE :likeText
                  OR u.username ILIKE :likeText)
             AND (:minRating IS NULL OR (pop.review_count > 0 AND pop.avg_rating >= :minRating))
             AND (:minSold IS NULL OR COALESCE(pop.sales_count, 0) >= :minSold)
             AND (:inStockDisabled = TRUE OR p.stock_quantity > 0)
           ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) DESC,
                    p.created_at DESC
           """,
           countQuery = """
           SELECT count(*) FROM products p
           LEFT JOIN users u ON u.id = p.vendor_id
           LEFT JOIN product_popularity pop ON pop.product_id = p.id
           WHERE p.deleted_at IS NULL
             AND (:categoryFilterDisabled = TRUE OR p.category_id IN (:categoryIds))
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor_id = :vendorId)
             AND (p.search_vector @@ to_tsquery('english', :tsQuery)
                  OR u.business_name ILIKE :likeText
                  OR u.username ILIKE :likeText)
             AND (:minRating IS NULL OR (pop.review_count > 0 AND pop.avg_rating >= :minRating))
             AND (:minSold IS NULL OR COALESCE(pop.sales_count, 0) >= :minSold)
             AND (:inStockDisabled = TRUE OR p.stock_quantity > 0)
           """,
           nativeQuery = true)
    Page<Product> searchRanked(@Param("categoryFilterDisabled") boolean categoryFilterDisabled,
                               @Param("categoryIds") List<Long> categoryIds,
                               @Param("handmade") Boolean handmade,
                               @Param("vendorId") Long vendorId,
                               @Param("tsQuery") String tsQuery,
                               @Param("likeText") String likeText,
                               @Param("minRating") BigDecimal minRating,
                               @Param("minSold") Long minSold,
                               @Param("inStockDisabled") boolean inStockDisabled,
                               Pageable pageable);

    /**
     * Browse path with popularity filters and a whitelist rank (sales |
     * rating | price | created). Native so ORDER BY can switch on the
     * sanitised rank token without exposing a column name to the caller.
     * Separate from findFiltered: the unranked browse is every nav click
     * and is not worth risking for ranking.
     */
    @Query(value = """
           SELECT p.* FROM products p
           LEFT JOIN users u ON u.id = p.vendor_id
           LEFT JOIN product_popularity pop ON pop.product_id = p.id
           WHERE p.deleted_at IS NULL
             AND (:categoryFilterDisabled = TRUE OR p.category_id IN (:categoryIds))
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor_id = :vendorId)
             AND (:searchDisabled = TRUE
                  OR LOWER(p.name) LIKE LOWER(CONCAT('%', :searchText, '%'))
                  OR LOWER(u.username) LIKE LOWER(CONCAT('%', :searchText, '%')))
             AND (:minRating IS NULL OR (pop.review_count > 0 AND pop.avg_rating >= :minRating))
             AND (:minSold IS NULL OR COALESCE(pop.sales_count, 0) >= :minSold)
             AND (:inStockDisabled = TRUE OR p.stock_quantity > 0)
           ORDER BY
             CASE WHEN :rank = 'price' THEN p.price END ASC,
             CASE WHEN :rank = 'sales' THEN COALESCE(pop.sales_count, 0) END DESC,
             CASE WHEN :rank = 'rating' THEN COALESCE(pop.weighted_rating, 0) END DESC,
             CASE WHEN :rank = 'created' THEN p.created_at END DESC,
             p.id ASC
           """,
           countQuery = """
           SELECT count(*) FROM products p
           LEFT JOIN users u ON u.id = p.vendor_id
           LEFT JOIN product_popularity pop ON pop.product_id = p.id
           WHERE p.deleted_at IS NULL
             AND (:categoryFilterDisabled = TRUE OR p.category_id IN (:categoryIds))
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor_id = :vendorId)
             AND (:searchDisabled = TRUE
                  OR LOWER(p.name) LIKE LOWER(CONCAT('%', :searchText, '%'))
                  OR LOWER(u.username) LIKE LOWER(CONCAT('%', :searchText, '%')))
             AND (:minRating IS NULL OR (pop.review_count > 0 AND pop.avg_rating >= :minRating))
             AND (:minSold IS NULL OR COALESCE(pop.sales_count, 0) >= :minSold)
             AND (:inStockDisabled = TRUE OR p.stock_quantity > 0)
           """,
           nativeQuery = true)
    Page<Product> findFilteredRanked(@Param("categoryFilterDisabled") boolean categoryFilterDisabled,
                                     @Param("categoryIds") List<Long> categoryIds,
                                     @Param("handmade") Boolean handmade,
                                     @Param("vendorId") Long vendorId,
                                     @Param("searchDisabled") boolean searchDisabled,
                                     @Param("searchText") String searchText,
                                     @Param("minRating") BigDecimal minRating,
                                     @Param("minSold") Long minSold,
                                     @Param("inStockDisabled") boolean inStockDisabled,
                                     @Param("rank") String rank,
                                     Pageable pageable);

    /**
     * "You might also like" — related products, computed from what the
     * catalogue already knows rather than from embeddings.
     *
     * There is no purchase or click history to mine (see the honest-signals
     * rule), so co-purchase and collaborative filtering would return nothing.
     * What DOES exist is text: the V21 search vector already carries each
     * product's name (weight A), the vendor's own tags (B) and description
     * (C). Two products that share vocabulary are related, and because tags
     * are in the vector, tag overlap is scored without a separate array
     * intersection.
     *
     * tsQuery is built from the SOURCE product's own words, OR-joined and
     * synonym-expanded, so "Rose Gold Watch set" also reaches listings that
     * say timepiece or jewellery. ts_rank then does the weighting: a shared
     * name word outranks a shared description word, which is the right
     * priority and is free.
     *
     * Same-category adds a flat bonus rather than being a filter. As a filter
     * it would collapse to "more from this category", which the category page
     * already is; as a bonus it lets a strong cross-category text match
     * through, which is where the interesting suggestions live.
     *
     * The 10x / 0.2 weighting is calibrated, not arbitrary. ts_rank returns
     * small absolute numbers (~0.05-0.3 even for a good match), so an
     * intuitive-looking "rank * 4 + 1 for same category" lets the category
     * bonus swamp the text score entirely: measured on the dev catalogue, a
     * search from "Fynbos Honey 500g" ranked an unrelated basket ABOVE two
     * other honeys purely because they shared a category. Scaling the rank up
     * and the bonus down puts a genuine word match first, which is the whole
     * point of the shelf.
     *
     * The WHERE is the relevance gate: a product must share SOME signal to
     * appear at all. Without it a thin catalogue pads the shelf with whatever
     * is newest, which looks like a recommendation but is not one.
     *
     * RETURNS THE SCORE, not just the order. SimilarityRanker blends this with
     * the popularity read model in Java, which it cannot do if SQL has already
     * collapsed the score into a row order. The calibrated 10x / 0.2 weighting
     * above is unchanged and still computed here, because it is a property of
     * the text match rather than of the blend.
     *
     * keywordMatch separates a real word overlap from a same-category-only
     * row. Both are allowed through the gate, but only the first is a claim
     * worth showing a shopper, so the ranker uses it for the reason string.
     * COALESCE is load-bearing, not defensive noise: @@ against a NULL
     * search_vector yields NULL rather than false, that row can still reach
     * the result set through the category branch of the WHERE, and unboxing
     * the NULL into the projection's primitive boolean would throw.
     *
     * Aliases are double-quoted deliberately: Postgres folds unquoted
     * identifiers to lower case, and the interface projection below binds by
     * exact alias name.
     */
    @Query(value = """
           SELECT p.id AS "productId",
                  ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) * 10
                    + CASE WHEN p.category_id = :categoryId THEN 0.2 ELSE 0 END AS "relevance",
                  COALESCE(p.search_vector @@ to_tsquery('english', :tsQuery), false) AS "keywordMatch"
           FROM products p
           WHERE p.deleted_at IS NULL
             AND p.id <> :productId
             AND p.stock_quantity > 0
             AND (p.search_vector @@ to_tsquery('english', :tsQuery)
                  OR p.category_id = :categoryId)
           ORDER BY "relevance" DESC, p.created_at DESC
           LIMIT :limit
           """, nativeQuery = true)
    List<ScoredCandidate> findSimilarScored(@Param("productId") Long productId,
                                            @Param("categoryId") Long categoryId,
                                            @Param("tsQuery") String tsQuery,
                                            @Param("limit") int limit);

    /** One lexical candidate and the score that earned it its place. */
    interface ScoredCandidate {
        Long getProductId();
        double getRelevance();
        boolean getKeywordMatch();
    }

    /**
     * ALL of one vendor's products, soft-deleted included — the vendor
     * dashboard shows archived items alongside live ones. Never expose this
     * on a public path.
     */
    Page<Product> findByVendorId(Long vendorId, Pageable pageable);

    /** Live product by id. Returns empty for soft-deleted products (public 404). */
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    /** Existence check for live products. Used by ReviewService browsing paths. */
    boolean existsByIdAndDeletedAtIsNull(Long id);

    /**
     * Live-only SKU check, matching the uq_products_sku_live partial index (V8):
     * a soft-deleted product's SKU is intentionally reusable.
     */
    boolean existsBySkuAndDeletedAtIsNull(String sku);

    /**
     * Pessimistic write lock (SELECT ... FOR UPDATE) for checkout stock
     * decrements. H2 supports FOR UPDATE but its semantics differ from
     * PostgreSQL — concurrency tests MUST use TestContainers PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * Batch lock in ascending id order. Consistent lock ordering across all
     * transactions is the deadlock-prevention strategy.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Product> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    Page<Product> findByIsActiveTrue(Pageable pageable);

    Optional<Product> findBySku(String sku);
}
