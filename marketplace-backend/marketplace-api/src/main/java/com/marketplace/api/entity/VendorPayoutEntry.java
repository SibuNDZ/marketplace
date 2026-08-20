package com.marketplace.api.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the commission ledger (V27): what eRestyu owes one vendor for
 * one order, computed and FROZEN at the moment the order went PAID.
 *
 * Every money field is a snapshot. The vendor's rate may change, the platform
 * default may change, the delivery fee may change — this row never does.
 * Corrections are new ADJUSTMENT rows, not edits: the ledger is append-only
 * history in the same way order_status_history is. VOID is the one status a
 * row can move to before money moves; nothing is ever deleted.
 *
 * Arithmetic (stated here so it cannot drift between writers):
 *   commission_amount = item_subtotal * commission_rate, rounded DOWN to 2dp
 *   net_payable       = item_subtotal - commission_amount + delivery_fee
 * Delivery fees pass through in full, never commissioned (vendor-payouts.md
 * §5). Rounding DOWN means the platform absorbs the sub-cent: the vendor is
 * never paid less than the exact split — a fraction of a cent per order comes
 * off the commission side, not the payout side.
 */
@Entity
@Table(name = "vendor_payout_entries")
public class VendorPayoutEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * NOT NULL, unlike OrderDeliveryFee.vendor — a payout entry is a debt to
     * a person and must not survive as an orphan. The FK is RESTRICT: a
     * vendor who is owed money cannot be deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private User vendor;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12)
    private PayoutEntryKind kind = PayoutEntryKind.PRIMARY;

    @Column(name = "item_subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal itemSubtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    /** The RESOLVED rate (vendor override or platform default) at write time. */
    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "commission_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "net_payable", nullable = false, precision = 10, scale = 2)
    private BigDecimal netPayable;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayoutEntryStatus status = PayoutEntryStatus.PENDING;

    /** Set when the entry is approved into a payout batch; null until then. */
    @Column(name = "batch_id")
    private Long batchId;

    /** Bank/EFT reference recorded when the batch is marked paid. */
    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Why a non-obvious row exists: adjustment reasons, void causes. */
    @Column(name = "note", length = 500)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public User getVendor() {
        return vendor;
    }

    public void setVendor(User vendor) {
        this.vendor = vendor;
    }

    public PayoutEntryKind getKind() {
        return kind;
    }

    public void setKind(PayoutEntryKind kind) {
        this.kind = kind;
    }

    public BigDecimal getItemSubtotal() {
        return itemSubtotal;
    }

    public void setItemSubtotal(BigDecimal itemSubtotal) {
        this.itemSubtotal = itemSubtotal;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public void setCommissionAmount(BigDecimal commissionAmount) {
        this.commissionAmount = commissionAmount;
    }

    public BigDecimal getNetPayable() {
        return netPayable;
    }

    public void setNetPayable(BigDecimal netPayable) {
        this.netPayable = netPayable;
    }

    public PayoutEntryStatus getStatus() {
        return status;
    }

    public void setStatus(PayoutEntryStatus status) {
        this.status = status;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
