package com.marketplace.api.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One vendor verification attempt (V38, paystack-subaccounts.md §5.1). The
 * latest row per vendor is the vendor's state; earlier rows are history.
 *
 * Append-only except for the decision: an admin writes outcome, verifiedBy,
 * verifiedAt and note exactly once. A changed bank account produces a new
 * PENDING row (VendorPayoutSettingsService), never a reset of this one.
 *
 * SENSITIVE, and deliberately thin: this row holds the LAST FOUR of the
 * account number and the LAST FOUR of the identity document, never either
 * in full. There is no field for the document number and none may be
 * added. toString() lists ids and states only; the holder name and both
 * last-4 values stay out of it, because entities get logged wholesale in
 * debug lines (the User.toString() rule).
 */
@Entity
@Table(name = "vendor_verifications")
public class VendorVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private User vendor;

    /** Null when the vendor saved banking without an identity document. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 32)
    private DocumentType documentType;

    @Column(name = "document_last4", length = 4)
    private String documentLast4;

    @Column(name = "account_holder_name", nullable = false, length = 120)
    private String accountHolderName;

    @Column(name = "bank_code", length = 16)
    private String bankCode;

    @Column(name = "account_last4", length = 4)
    private String accountLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20)
    private BankAccountType accountType;

    @Column(name = "provider", nullable = false, length = 16)
    private String provider = "PAYSTACK";

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_result", nullable = false, length = 16)
    private VerificationProviderResult providerResult = VerificationProviderResult.NOT_RUN;

    // The provider's flags (§3). All null until the validate call exists.
    @Column(name = "account_holder_match")
    private Boolean accountHolderMatch;

    @Column(name = "account_open")
    private Boolean accountOpen;

    @Column(name = "account_open_3m")
    private Boolean accountOpen3m;

    @Column(name = "accepts_credits")
    private Boolean acceptsCredits;

    @Column(name = "verification_message", length = 255)
    private String verificationMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private VerificationOutcome outcome = VerificationOutcome.PENDING;

    /** The admin who decided; null while PENDING. */
    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public User getVendor() {
        return vendor;
    }

    public void setVendor(User vendor) {
        this.vendor = vendor;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public void setDocumentType(DocumentType documentType) {
        this.documentType = documentType;
    }

    public String getDocumentLast4() {
        return documentLast4;
    }

    public void setDocumentLast4(String documentLast4) {
        this.documentLast4 = documentLast4;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public void setAccountHolderName(String accountHolderName) {
        this.accountHolderName = accountHolderName;
    }

    public String getBankCode() {
        return bankCode;
    }

    public void setBankCode(String bankCode) {
        this.bankCode = bankCode;
    }

    public String getAccountLast4() {
        return accountLast4;
    }

    public void setAccountLast4(String accountLast4) {
        this.accountLast4 = accountLast4;
    }

    public BankAccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(BankAccountType accountType) {
        this.accountType = accountType;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public VerificationProviderResult getProviderResult() {
        return providerResult;
    }

    public void setProviderResult(VerificationProviderResult providerResult) {
        this.providerResult = providerResult;
    }

    public Boolean getAccountHolderMatch() {
        return accountHolderMatch;
    }

    public void setAccountHolderMatch(Boolean accountHolderMatch) {
        this.accountHolderMatch = accountHolderMatch;
    }

    public Boolean getAccountOpen() {
        return accountOpen;
    }

    public void setAccountOpen(Boolean accountOpen) {
        this.accountOpen = accountOpen;
    }

    public Boolean getAccountOpen3m() {
        return accountOpen3m;
    }

    public void setAccountOpen3m(Boolean accountOpen3m) {
        this.accountOpen3m = accountOpen3m;
    }

    public Boolean getAcceptsCredits() {
        return acceptsCredits;
    }

    public void setAcceptsCredits(Boolean acceptsCredits) {
        this.acceptsCredits = acceptsCredits;
    }

    public String getVerificationMessage() {
        return verificationMessage;
    }

    public void setVerificationMessage(String verificationMessage) {
        this.verificationMessage = verificationMessage;
    }

    public VerificationOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(VerificationOutcome outcome) {
        this.outcome = outcome;
    }

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(Long verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
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

    /** Ids and states only: no holder name, no last-4 of anything. */
    @Override
    public String toString() {
        return "VendorVerification{" +
                "id=" + id +
                ", vendorId=" + (vendor == null ? null : vendor.getId()) +
                ", documentType=" + documentType +
                ", providerResult=" + providerResult +
                ", outcome=" + outcome +
                ", createdAt=" + createdAt +
                '}';
    }
}
