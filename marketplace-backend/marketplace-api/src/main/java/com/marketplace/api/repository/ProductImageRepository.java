package com.marketplace.api.repository;

import com.marketplace.api.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Ordering is always (position, id): position is the vendor's intent and id
 * breaks ties by insertion, so two images that somehow share a position still
 * come back in a stable order rather than whatever the planner chose.
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderByPositionAscIdAsc(Long productId);

    /**
     * The batch shape, and the only one a list should use. One query enriches
     * a whole catalogue page — the same rule as variants and popularity, for
     * the same reason.
     */
    List<ProductImage> findByProductIdInOrderByPositionAscIdAsc(Collection<Long> productIds);

    long countByProductId(Long productId);
}
