package com.marketplace.api.repository;

import com.marketplace.api.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * The purchase-verification query — the feature's core rule as one JPQL
     * statement: a user may review a product iff some DELIVERED order of
     * theirs contains it. Verification is a live query against order history,
     * not a stored flag; it cannot drift from the truth.
     *
     * Paths confirmed against entities: OrderItem.order → Order,
     * Order.user → User, OrderItem.product → Product (optional=true, hard-
     * deleted products yield null and are filtered by the equality condition).
     */
    @Query("""
            SELECT COUNT(oi) > 0 FROM OrderItem oi
            WHERE oi.order.user.id = :userId
              AND oi.product.id    = :productId
              AND oi.order.status  = com.marketplace.api.entity.OrderStatus.DELIVERED
            """)
    boolean hasDeliveredPurchase(@Param("userId") Long userId,
                                 @Param("productId") Long productId);

    boolean existsByUserIdAndProductId(Long userId, Long productId);

    Optional<Review> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = {"user"})
    Page<Review> findByProductId(Long productId, Pageable pageable);

    @Query("""
            SELECT COALESCE(AVG(r.rating), 0), COUNT(r)
            FROM Review r WHERE r.product.id = :productId
            """)
    Object[] ratingAggregate(@Param("productId") Long productId);
}
