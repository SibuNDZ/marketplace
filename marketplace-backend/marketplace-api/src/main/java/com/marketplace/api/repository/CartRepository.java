package com.marketplace.api.repository;

import com.marketplace.api.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * Acquires a pessimistic write lock on the cart row before returning it.
     * Used as the FIRST lock in placeOrder — cart is locked before products —
     * so that two concurrent same-user checkout attempts block on the cart row
     * rather than racing. The second call sees the cleared cart and throws
     * EmptyCartException, making duplicate orders impossible.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Cart c WHERE c.user.id = :userId")
    Optional<Cart> findByUserIdForUpdate(@Param("userId") Long userId);
}

