package com.marketplace.api.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    @Email(message = "Please provide a valid email address")
    @NotBlank(message = "Email is required")
    private String email;

    @Column(nullable = false)
    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @Column(name = "first_name", nullable = false)
    @NotBlank(message = "First name is required")
    private String firstName;

    // NOT @NotBlank: mononyms are common and a required surname rejects real
    // people. The column stays NOT NULL and an absent surname is stored as
    // "". The previous @NotBlank here turned every single-name registration
    // into an opaque 500 at flush time.
    @Column(name = "last_name", nullable = false)
    private String lastName;

    // Stored lowercase — the DB has a plain UNIQUE constraint, so casing is
    // normalised on the way in (AuthService) rather than relying on a
    // functional index Hibernate's validate mode cannot see.
    @Column(name = "username", nullable = false, length = 30, unique = true)
    @NotBlank(message = "Username is required")
    private String username;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "is_active")
    private Boolean isActive = true;

    // "Has this person proved they own this address", NOT "is this account
    // allowed in" — that is isActive, which UserPrincipal maps to isEnabled().
    // Login checks both, for different reasons.
    @Column(name = "is_verified", nullable = false)
    private Boolean isVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role = UserRole.CUSTOMER;

    /**
     * The name this vendor trades under, shown on every product card (V19).
     * Null for buyers. Required for vendors, enforced at registration and at
     * the buyer->vendor upgrade rather than by a column constraint that
     * would also bind customers.
     */
    @Column(name = "business_name", length = 200)
    private String businessName;

    /**
     * Flat delivery fee this vendor charges per order (V16). Meaningful only
     * for VENDOR rows; 0 means free delivery. This is the vendor's CURRENT
     * fee — orders snapshot it into OrderDeliveryFee at placement, so edits
     * here never reprice existing orders.
     */
    @Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    /**
     * Per-vendor commission override (V27), a FRACTION (0.15 = 15%). NULL
     * means "use the platform default from app.payouts.commission-rate".
     * This is the CURRENT rate: the resolved rate is snapshotted onto each
     * payout entry at PAID time, so edits here never rewrite what an
     * existing entry owes — same principle as deliveryFee above.
     */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;

    // ── Vendor banking details (V28) ────────────────────────────────────
    // SENSITIVE. These fields must never appear in a log line or an API
    // response unmasked — masking (last 4 of the account number, one method,
    // shippingFor()-style) is the only way they leave the server. The
    // toString below enumerates its fields explicitly; banking must NEVER be
    // added to it, because entities get logged wholesale in debug lines.
    // Completeness is a selling gate enforced in the application, not a
    // column constraint: customers and not-yet-onboarded vendors
    // legitimately have all-null rows.

    @Column(name = "account_holder_name", length = 120)
    private String accountHolderName;

    @Column(name = "bank_name", length = 80)
    private String bankName;

    @Column(name = "account_number", length = 20)
    private String accountNumber;

    @Column(name = "branch_code", length = 10)
    private String branchCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20)
    private BankAccountType accountType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Relationships
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Review> reviews = new ArrayList<>();

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Cart cart;

    // Constructors
    public User() {}

    public User(String email, String password, String firstName, String lastName) {
        this.email = email;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getIsVerified() {
        return isVerified;
    }

    public void setIsVerified(Boolean isVerified) {
        this.isVerified = isVerified;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public void setOrders(List<Order> orders) {
        this.orders = orders;
    }

    public List<Review> getReviews() {
        return reviews;
    }

    public void setReviews(List<Review> reviews) {
        this.reviews = reviews;
    }

    public Cart getCart() {
        return cart;
    }

    public void setCart(Cart cart) {
        this.cart = cart;
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

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public void setAccountHolderName(String accountHolderName) {
        this.accountHolderName = accountHolderName;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode;
    }

    public BankAccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(BankAccountType accountType) {
        this.accountType = accountType;
    }

    /**
     * The banking-completeness rule, in ONE place — the selling gate, the
     * settings UI and the exporter all ask this same method, so they can
     * never disagree about what "complete" means.
     */
    public boolean hasCompleteBankingDetails() {
        return accountHolderName != null && !accountHolderName.isBlank()
                && bankName != null && !bankName.isBlank()
                && accountNumber != null && !accountNumber.isBlank()
                && branchCode != null && !branchCode.isBlank()
                && accountType != null;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    // Utility methods
    public String getFullName() {
        return (firstName + " " + (lastName == null ? "" : lastName)).trim();
    }

    /**
     * What the public sees attributed to this user on a listing: the trading
     * name, never the person's name. The fallback exists only for rows that
     * predate V19's backfill or were created by fixtures; a vendor with no
     * business name is a data defect, not a display case worth designing for.
     */
    public String getStorefrontName() {
        return businessName == null || businessName.isBlank() ? getFullName() : businessName;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", role=" + role +
                ", isActive=" + isActive +
                ", createdAt=" + createdAt +
                '}';
    }
}