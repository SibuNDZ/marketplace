package com.marketplace.api.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One photo belonging to a product (V24).
 *
 * Replaces the single products.image_key. This table is now the ONLY place a
 * product photo lives; ProductResponse.imageUrl is derived from the first row
 * so that cards, cart lines and rails keep working without knowing about the
 * gallery at all.
 *
 * Stores the R2 KEY, never the URL, for the same reason V11 did: the serving
 * domain can change without a data migration.
 *
 * Deliberately NOT mapped as a collection on Product. A @OneToMany would be
 * fetched for every product in every catalogue page, and the response mapper
 * already batches by id the same way it does for variants and popularity.
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "image_key", nullable = false, length = 500)
    private String imageKey;

    /** 0-based display order. Ties break on id, so ordering is always total. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public String getImageKey() { return imageKey; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
