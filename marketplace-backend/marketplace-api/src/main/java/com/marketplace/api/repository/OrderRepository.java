package com.marketplace.api.repository;

import com.marketplace.api.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
