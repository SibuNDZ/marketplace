package com.marketplace.api.repository;

import com.marketplace.api.entity.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
