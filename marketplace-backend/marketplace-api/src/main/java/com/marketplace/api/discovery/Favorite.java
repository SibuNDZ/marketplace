package com.marketplace.api.discovery;

import com.marketplace.api.entity.BaseEntity;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import jakarta.persistence.*;

/**
 * Explicit "like". Worth an order of magnitude more per-event than a view
 * as a preference signal, and a user feature in its own right.
 *
 * Extends BaseEntity: id, created_at, updated_at are all meaningful here
 * (when did the user heart this?). Unlike ProductView this IS mutable in
 * the toggle sense — the row is deleted on unfavorite — so the audit base
 * is the right fit.
 *
 * The unique(user, product) constraint in V9 is the double-submit backstop
 * (same philosophy as reviews): FavoriteService checks existsBy first for
 * a clean idempotent path, constraint catches the race. In practice the
 * toggle endpoints are idempotent so the constraint fires only under
 * genuine concurrent first-taps.
 */
@Entity
@Table(name = "favorites")
public class Favorite extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
}
