package com.marketplace.api.discovery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    Optional<Favorite> findByUserIdAndProductId(Long userId, Long productId);

    boolean existsByUserIdAndProductId(Long userId, Long productId);

    /**
     * Favorites page: live products only. Soft-deleted products drop out
     * silently — a heart on a ghost helps nobody. The Favorite ROW survives,
     * so relisting the product would restore the heart for free (consistent
     * with soft-delete preserving history).
     */
    @EntityGraph(attributePaths = {"product", "product.vendor"})
    Page<Favorite> findByUserIdAndProductDeletedAtIsNull(Long userId, Pageable pageable);

    /** Batch heart-state lookup for a visible product grid. */
    List<Favorite> findByUserIdAndProductIdIn(Long userId, List<Long> productIds);

    /**
     * Heart-state for the whole UI in ONE query: every live favorited
     * product id. The frontend caches this as a Set so each card can
     * answer "is this hearted?" without a request per product.
     */
    @Query("select f.product.id from Favorite f "
            + "where f.user.id = :userId and f.product.deletedAt is null")
    List<Long> liveProductIds(@Param("userId") Long userId);
}
