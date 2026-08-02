package com.marketplace.api.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One purchasable option of a product: "Black", "XL", "2pcs black" (V20).
 *
 * The stock rule, stated once here because it is the thing that will be got
 * wrong later: a product either has NO variants and uses its own
 * stockQuantity and price, or it HAS variants and they are the only place
 * stock and price live. The product's own stockQuantity is then ignored and
 * never written; ProductResponse computes stock as the SUM of variant stock
 * and price as the MINIMUM variant price at read time.
 *
 * That sum is computed, not stored, on purpose. Maintaining products.stock
 * as a running total of its variants would be a dual write, and dual writes
 * drift; a computed sum cannot disagree with itself.
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** What the buyer picks. Unique per product. */
    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "sku", length = 100)
    private String sku;

    /** Absolute, not a delta off the product price. */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    /** Optional per-variant photo (R2 key), for colour. Null is normal. */
    @Column(name = "image_key", length = 500)
    private String imageKey;

    /** Vendor-controlled display order; ties broken by id. */
    @Column(name = "position", nullable = false)
    private Integer position = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ProductVariant() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }

    public String getImageKey() { return imageKey; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
