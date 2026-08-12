package com.marketplace.api.repository;

import com.marketplace.api.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
           WHERE p.deleted_at IS NULL
             AND (:categoryFilterDisabled = TRUE OR p.category_id IN (:categoryIds))
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor_id = :vendorId)
             AND (p.search_vector @@ to_tsquery('english', :tsQuery)
                  OR u.business_name ILIKE :likeText
                  OR u.username ILIKE :likeText)
           ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) DESC,
                    p.created_at DESC
           """,
           countQuery = """
           SELECT count(*) FROM products p
           LEFT JOIN users u ON u.id = p.vendor_id
           WHERE p.deleted_at IS NULL
             AND (:categoryFilterDisabled = TRUE OR p.category_id IN (:categoryIds))
             AND (:handmade IS NULL OR p.handmade = :handmade)
             AND (:vendorId IS NULL OR p.vendor_id = :vendorId)
             AND (p.search_vector @@ to_tsquery('english', :tsQuery)
                  OR u.business_name ILIKE :likeText
                  OR u.username ILIKE :likeText)
           """,
           nativeQuery = true)
    Page<Product> searchRanked(@Param("categoryFilterDisabled") boolean categoryFilterDisabled,
                               @Param("categoryIds") List<Long> categoryIds,
                               @Param("handmade") Boolean handmade,
                               @Param("vendorId") Long vendorId,
                               @Param("tsQuery") String tsQuery,
                               @Param("likeText") String likeText,
                               Pageable pageable);

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
