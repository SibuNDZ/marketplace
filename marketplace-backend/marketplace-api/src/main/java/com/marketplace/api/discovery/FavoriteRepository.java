package com.marketplace.api.discovery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
