package com.marketplace.api.entity;

import jakarta.persistence.*;

/**
 * One row per status transition, append-only.
 *
 * Extends BaseEntity for the shared {@code @Id}/{@code @GeneratedValue} and
 * audit timestamps (createdAt, updatedAt via Spring Data JPA Auditing).
 * Do NOT redeclare id here — BaseEntity already provides it.
 *
 * {@code fromStatus} is nullable: the creation event (null -> PENDING) is
 * recorded at placement so the history is complete from birth and
 * "when was this ordered" has the same answer shape as "when did it ship".
 */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private OrderStatus toStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by")
    private User changedBy;

    @Column(length = 500)
    private String note;

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public OrderStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(OrderStatus fromStatus) { this.fromStatus = fromStatus; }
    public OrderStatus getToStatus() { return toStatus; }
    public void setToStatus(OrderStatus toStatus) { this.toStatus = toStatus; }
    public User getChangedBy() { return changedBy; }
    public void setChangedBy(User changedBy) { this.changedBy = changedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
