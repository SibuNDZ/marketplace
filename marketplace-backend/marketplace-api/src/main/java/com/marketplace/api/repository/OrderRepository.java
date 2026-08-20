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
import java.util.Collection;
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

    /**
     * Full graph for the order-email listener: buyer, items, and each item's
     * vendor in one round trip. The listener reads the graph after its
     * transaction closes (entities detached), so everything it touches must
     * be fetched here — a missing path is a LazyInitializationException at
     * send time, not compile time.
     */
    @EntityGraph(attributePaths = {"user", "orderItems", "orderItems.product",
            "orderItems.product.vendor", "deliveryFees"})
    Optional<Order> findWithItemsAndVendorsById(Long id);

    Page<Order> findByUserId(Long userId, Pageable pageable);

    /**
     * A buyer's orders narrowed to a set of statuses. A SET, not a single
     * status, because one tab legitimately covers two: "Returns & cancelled"
     * is CANCELLED plus REFUNDED, and collapsing them into separate tabs
     * would show buyers a distinction they do not think in.
     */
    Page<Order> findByUserIdAndStatusIn(Long userId, Collection<OrderStatus> statuses, Pageable pageable);

    /** Per-status counts for one buyer, so tab badges need no extra fetch. */
    @Query("select o.status, count(o) from Order o where o.user.id = :userId group by o.status")
    List<Object[]> countByStatusForUser(@Param("userId") Long userId);

    /**
     * Admin list views. EntityGraph on user (a to-one) keeps customerEmail out
     * of N+1 territory and is pagination-safe — unlike fetching the orderItems
     * collection, which would force Hibernate into in-memory paging.
     */
    @EntityGraph(attributePaths = {"user"})
    Page<Order> findBy(Pageable pageable);

    @EntityGraph(attributePaths = {"user"})
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /**
     * Orders that contain at least one item from this vendor, restricted to
     * the given statuses (the vendor view passes PAID-and-later: a vendor has
     * no business seeing carts that may still expire unpaid). DISTINCT because
     * the item join multiplies rows; the explicit count query keeps Spring
     * Data from mis-deriving one over the join.
     */
    @Query(value = """
            select distinct o from Order o join o.orderItems i
            where i.product.vendor.id = :vendorId and o.status in :statuses
            """,
            countQuery = """
            select count(distinct o) from Order o join o.orderItems i
            where i.product.vendor.id = :vendorId and o.status in :statuses
            """)
    Page<Order> findVendorOrders(@Param("vendorId") Long vendorId,
                                 @Param("statuses") Collection<OrderStatus> statuses,
                                 Pageable pageable);

    /**
     * Order ids from the given set that contain any item NOT belonging to this
     * vendor (a deleted product's item counts: its vendor is unknowable, so
     * the order is not provably single-vendor). Complement of "canShip".
     */
    @Query("""
            select distinct i.order.id from OrderItem i
            where i.order.id in :orderIds
              and (i.product is null or i.product.vendor.id <> :vendorId)
            """)
    List<Long> idsWithForeignItems(@Param("orderIds") Collection<Long> orderIds,
                                   @Param("vendorId") Long vendorId);

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

    /**
     * Orders that reached PAID (in any of its later states too) before the
     * commission ledger existed — i.e. with money kept but no payout entries.
     * The backfill runner's work list. Ascending id: oldest debt first.
     */
    @Query("""
            SELECT o.id FROM Order o
            WHERE o.status IN :statuses
              AND NOT EXISTS (SELECT 1 FROM VendorPayoutEntry e WHERE e.order.id = o.id)
            ORDER BY o.id ASC
            """)
    List<Long> findIdsMissingPayoutEntries(@Param("statuses") Collection<OrderStatus> statuses);

    long countByUserId(Long userId);
}
