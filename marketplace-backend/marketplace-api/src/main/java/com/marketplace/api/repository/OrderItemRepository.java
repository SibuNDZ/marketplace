package com.marketplace.api.repository;

import com.marketplace.api.entity.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Exists for the vendor order view, which needs "this vendor's items across
 * these orders" WITHOUT touching Order.orderItems — fetching that to-many
 * collection for a page of orders is exactly the in-memory-paging trap the
 * admin list queries already avoid (see OrderRepository.findBy).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @EntityGraph(attributePaths = {"product"})
    List<OrderItem> findByOrderIdInAndProductVendorId(Collection<Long> orderIds, Long vendorId);
}
