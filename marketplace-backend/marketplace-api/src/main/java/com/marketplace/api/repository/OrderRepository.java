package com.marketplace.api.repository;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * EntityGraph avoids N+1 when rendering an order with its items.
     * Field paths use the JPA field names on Order (orderItems) and
     * OrderItem (product).
     */
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    Optional<Order> findWithItemsById(Long id);

    Page<Order> findByUserId(Long userId, Pageable pageable);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /**
     * Pessimistic write lock on the order row. Used by the payment webhook and
     * the expiry job to serialize the PENDING -> PAID vs PENDING -> CANCELLED
     * decision — whoever locks first wins, the other backs off cleanly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /**
     * Finds PENDING orders whose creation time is before the given cutoff.
     * Used by the expiry job to locate stale, unpaid orders.
     * createdAt is LocalDateTime (matches Order entity's @CreationTimestamp field).
     */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);

    long countByUserId(Long userId);
}
