package com.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The admin vendor-verification screen (paystack-subaccounts.md §4.3).
 * NOTHING in here carries a full account number or a full document
 * number: banking is the last four plus the fields the provider verifies,
 * identity is the type plus the last four. There is no field to put the
 * full values in, which is the point.
 */
public class VendorVerificationDtos {

    /** One of the vendor's most recent live listings, for the reviewer's eye. */
    public record ListingSummary(
            Long id,
            String name,
            BigDecimal price,
            String imageUrl
    ) {}

    /** A vendor whose latest verification row is PENDING. */
    public record PendingVendor(
            Long vendorId,
            String businessName,
            String email,
            LocalDateTime submittedAt,
            String accountHolderName,
            String bankCode,
            String bankName,
            String accountLast4,      // e.g. "···6789"
            String accountType,
            String documentType,      // ID_NUMBER | PASSPORT | CIPC | null
            String documentLast4,     // e.g. "···4321"; null when no document was supplied
            String providerResult,    // OK | MISMATCH | UNAVAILABLE | NOT_RUN
            String verificationMessage,
            // The provider's flags (spec §3); null until a check has run.
            Boolean accountHolderMatch,
            Boolean accountOpen,
            Boolean accountOpen3m,
            Boolean acceptsCredits,
            List<ListingSummary> listings
    ) {}

    /** Note optional on approve. */
    public record ApproveRequest(@Size(max = 1000) String note) {}

    /** Note required on reject: the vendor is told why, so there must be a why. */
    public record RejectRequest(@NotBlank @Size(max = 1000) String note) {}

    /** The vendor's state after a decision (or the unchanged state, when already decided). */
    public record DecisionResponse(
            Long vendorId,
            Long verificationId,
            String outcome,           // PENDING | APPROVED | REJECTED
            String providerResult,
            Long verifiedBy,
            LocalDateTime verifiedAt,
            String note,
            LocalDateTime vendorApprovedAt
    ) {}
}
