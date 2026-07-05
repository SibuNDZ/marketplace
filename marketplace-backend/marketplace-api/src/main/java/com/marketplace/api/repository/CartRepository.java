package com.marketplace.api.repository;

import com.marketplace.api.entity.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads the cart with items and their products in one query. Checkout
     * reads every item anyway, so eager-fetch here is deliberate.
     * EntityGraph paths use the JPA field names on Cart (cartItems) and
     * CartItem (product).
     */
    @EntityGraph(attributePaths = {"cartItems", "cartItems.product"})
    Optional<Cart> findWithItemsByUserId(Long userId);

    Optional<Cart> findByUserId(Long userId);

    /**
     * Acquires a pessimistic write lock on the cart row and returns the cart's
     * primary key. Returns {@code Optional.empty()} if no cart exists for the
     * user (caller should throw {@code CartNotFoundException}).
     * <p>
     * <b>Why native SQL:</b> {@code @Lock(PESSIMISTIC_WRITE)} on a JPQL query
     * whose WHERE clause traverses an association path ({@code c.user.id}) does
     * not reliably emit {@code FOR UPDATE} in Hibernate 6 — empirically observed
     * as two concurrent same-user checkouts both succeeding.
     * <p>
     * <b>Why return Long, not Cart:</b> returning a Cart entity would place it
     * in the Hibernate first-level cache. A subsequent {@link #findWithItemsByUserId}
     * call then risks serving the cached (pre-commit) entity to Thread B instead
     * of reading the current DB state — defeating the double-submit guard.
     * A scalar result leaves the cache clean so the EntityGraph reload is fresh.
     * <p>
     * <b>Lock order:</b> cart first, then products ({@code lockAndRefresh} in
     * {@code OrderService}). Both concurrent checkout calls take locks in the
     * same sequence, preventing deadlocks.
     */
    @Query(value = "SELECT id FROM carts WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> findByUserIdForUpdate(@Param("userId") Long userId);
}

