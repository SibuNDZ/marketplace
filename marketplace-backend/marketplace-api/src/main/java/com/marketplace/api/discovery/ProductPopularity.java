package com.marketplace.api.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only JPA view over the product_popularity read model (V9). Exists so
 * services can BATCH-fetch aggregates when mapping product responses —
 * the table is written exclusively by PopularityJob's native upsert, never
 * through this entity.
 *
 * @Immutable is the enforcement, not just documentation: Hibernate ignores
 * dirty-checking on it and throws on explicit persist/merge attempts, so
 * nobody can accidentally write the read model through the ORM and fight
 * the job's ON CONFLICT rewrite.
 *
 * ADAPTATION: column types must match V9 exactly for ddl validate —
 * avg_rating NUMERIC(3,2) and weighted_rating NUMERIC(4,3) map to
 * BigDecimal with those precisions; if validate complains, the fix is
 * here, not in the migration.
 */
@Entity
@Immutable
@Table(name = "product_popularity")
public class ProductPopularity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "sales_count", nullable = false)
    private long salesCount;

    @Column(name = "review_count", nullable = false)
    private long reviewCount;

    @Column(name = "avg_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal avgRating;

    @Column(name = "weighted_rating", nullable = false, precision = 4, scale = 3)
    private BigDecimal weightedRating;   // ranking-only; not exposed in DTOs

    @Column(name = "views_30d", nullable = false)
    private long views30d;               // internal; not exposed in DTOs

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    public Long getProductId() { return productId; }
    public long getSalesCount() { return salesCount; }
    public long getReviewCount() { return reviewCount; }
    public BigDecimal getAvgRating() { return avgRating; }
    public BigDecimal getWeightedRating() { return weightedRating; }
    public long getViews30d() { return views30d; }
    public LocalDateTime getComputedAt() { return computedAt; }
}
