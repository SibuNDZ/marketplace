package com.marketplace.api.discovery;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One row per product-detail view. Deliberately NOT extending BaseEntity:
 * an event has one timestamp (when it happened), never an updated_at,
 * because events are never updated. Extending the audit base would add a
 * dead column and imply mutability that doesn't exist.
 *
 * user is nullable — the detail endpoint is public. Anonymous views feed
 * trending (views_30d in the popularity table); only authenticated views
 * feed recently-viewed.
 *
 * POPIA note: this is behavioral data about identified users. Purpose:
 * recommendations. Retention: 90 days (enforced by PopularityJob.sweepOldViews).
 * The nullable user FK also permits anonymize-instead-of-delete when the
 * account-deletion feature lands.
 */
@Entity
@Table(name = "product_views")
public class ProductView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;                         // null = anonymous

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @CreationTimestamp
    @Column(name = "viewed_at", nullable = false, updatable = false)
    private LocalDateTime viewedAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public LocalDateTime getViewedAt() { return viewedAt; }
}
