package com.marketplace.api.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * One flat delivery charge per vendor per order, snapshotted at placement
 * time — the same immutability contract as OrderItem's priceAtPurchase.
 * The vendor reference is optional so order history survives account
 * deletion; vendorNameAtPurchase carries the display name regardless.
 */
@Entity
@Table(name = "order_delivery_fees")
public class OrderDeliveryFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "vendor_id", nullable = true)
    private User vendor;

    @Column(name = "vendor_name_at_purchase", nullable = false)
    private String vendorNameAtPurchase;

    @Column(name = "fee_at_purchase", nullable = false, precision = 10, scale = 2)
    private BigDecimal feeAtPurchase;

    public OrderDeliveryFee() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public User getVendor() { return vendor; }
    public void setVendor(User vendor) { this.vendor = vendor; }

    public String getVendorNameAtPurchase() { return vendorNameAtPurchase; }
    public void setVendorNameAtPurchase(String name) { this.vendorNameAtPurchase = name; }

    public BigDecimal getFeeAtPurchase() { return feeAtPurchase; }
    public void setFeeAtPurchase(BigDecimal fee) { this.feeAtPurchase = fee; }
}
