package com.marketplace.api.repository;

import com.marketplace.api.entity.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * How many DISTINCT people bought this product in the last 24 hours.
     *
     * Counts people, not orders and not units, because the sentence it feeds
     * says "N people bought this". Someone who ordered three times in a day is
     * one person, and one order of twelve units is one person.
     *
     * Status filter matches PopularityJob exactly: PAID/SHIPPED/DELIVERED are
     * money that arrived, while PENDING (no money), CANCELLED and REFUNDED are
     * not purchases. Divergence between the two definitions of "sold" would be
     * a bug waiting to happen, so they are deliberately identical.
     *
     * Self-purchases are excluded. A vendor buying their own listing would
     * otherwise manufacture social proof for it, which is the one failure mode
     * that matters for a signal whose entire job is to be trustworthy.
     * IS DISTINCT FROM rather than <> so a null on either side still counts.
     *
     * The window is measured from order creation, not from payment. Orders in
     * this system are paid within minutes or expired by OrderExpiryJob, so the
     * two are near-identical, and orders carry no paid_at column to use
     * instead. If a payment method with a long settlement delay is ever added,
     * this is the line to revisit.
     */
    @Query(value = """
           SELECT COUNT(DISTINCT o.user_id)
           FROM order_items oi
           JOIN orders o   ON o.id = oi.order_id
           JOIN products p ON p.id = oi.product_id
           WHERE oi.product_id = :productId
             AND o.status IN ('PAID', 'SHIPPED', 'DELIVERED')
             AND o.created_at > now() - INTERVAL '24 hours'
             AND o.user_id IS DISTINCT FROM p.vendor_id
           """, nativeQuery = true)
    long countDistinctRecentBuyers(@Param("productId") Long productId);
}
