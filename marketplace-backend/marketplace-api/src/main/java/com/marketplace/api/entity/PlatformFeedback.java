package com.marketplace.api.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One piece of private user-to-operator feedback (V18). Append-only by
 * design: the message is a statement made at a point in time, like an order
 * history row; only the operator-side status flag ever changes.
 */
@Entity
@Table(name = "platform_feedback")
public class PlatformFeedback {

    public enum Category { BUG, IDEA, COMPLAINT, PRAISE, OTHER }

    public enum Status { NEW, REVIEWED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.NEW;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PlatformFeedback() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
