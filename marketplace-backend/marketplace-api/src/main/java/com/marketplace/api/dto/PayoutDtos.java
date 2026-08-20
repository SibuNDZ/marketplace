package com.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin payout surfaces. NOTHING in here carries a full account number —
 * banking appears only as {@link MaskedBanking}, built by the single masking
 * method in PayoutAdminService. The full number exists in exactly one output:
 * the bank CSV, which is not a DTO.
 */
public class PayoutDtos {

    /** Banking as the UI is allowed to see it: last 4, nothing else. */
    public record MaskedBanking(
            String bankName,
            String accountNumberLast4, // e.g. "···6789"; null when not captured
            boolean complete           // all five fields present on the vendor
    ) {}

    public record PendingEntry(
            Long id,
            Long orderId,
            String orderNumber,
            String kind,          // PRIMARY | ADJUSTMENT
            LocalDateTime createdAt,
            BigDecimal itemSubtotal,
            BigDecimal deliveryFee,
            BigDecimal commissionAmount,
            BigDecimal netPayable,
            String note
    ) {}

    /** One vendor's group in the pending view, with the sum the bank would pay. */
    public record VendorPendingGroup(
            Long vendorId,
            String displayName,   // business name, falling back to the person
            MaskedBanking banking,
            List<PendingEntry> entries,
            BigDecimal totalNet
    ) {}

    public record ApproveBatchRequest(
            @NotEmpty List<Long> entryIds
    ) {}

    public record MarkPaidRequest(
            @NotBlank @Size(max = 100) String paymentReference
    ) {}

    public record BatchSummary(
            Long id,
            LocalDateTime approvedAt,
            LocalDateTime exportedAt,
            LocalDateTime paidAt,
            String paymentReference,
            int entryCount,
            int vendorCount,
            BigDecimal totalNet,
            /** Derived, never stored: APPROVED -> EXPORTED -> PAID. */
            String state
    ) {}
}
