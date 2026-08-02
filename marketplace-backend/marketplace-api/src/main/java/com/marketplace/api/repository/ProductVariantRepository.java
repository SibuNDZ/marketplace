package com.marketplace.api.repository;

import com.marketplace.api.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductIdOrderByPositionAscIdAsc(Long productId);

    /** Batch load for a page of products, so a grid does not fire one query per card. */
    List<ProductVariant> findByProductIdInOrderByPositionAscIdAsc(Collection<Long> productIds);

    /**
     * Step 2 will decrement these rows. The lock is held on the PRODUCT row
     * (see product-variants.md §5, option B) so the existing ascending-
     * product-id deadlock discipline in OrderService survives unchanged;
     * this query just loads the variants inside that lock.
     */
    @Query("select v from ProductVariant v where v.id in :ids")
    List<ProductVariant> findAllByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * Belt-and-braces row lock for any future path that must mutate variant
     * stock WITHOUT already holding the product lock. Not used by the order
     * path — that one locks the product. Present so a later caller does not
     * invent its own locking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id in :ids order by v.id")
    List<ProductVariant> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    boolean existsByProductIdAndLabelIgnoreCase(Long productId, String label);

    long countByProductId(Long productId);
}
