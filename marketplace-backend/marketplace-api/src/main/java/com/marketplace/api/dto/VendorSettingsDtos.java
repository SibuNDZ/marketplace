package com.marketplace.api.dto;

import com.marketplace.api.entity.BankAccountType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class VendorSettingsDtos {

    public record VendorSettingsResponse(BigDecimal deliveryFee) {}

    /**
     * The upper bound is a sanity rail, not a business rule: a fee above
     * R10,000 is a typo (extra zero, cents entered as rand), and rejecting
     * it at the API beats a vendor silently pricing themselves out of every
     * sale. Adjust when a real courier quote ever exceeds it.
     */
    public record VendorSettingsRequest(
            @NotNull
            @DecimalMin(value = "0.00", message = "Delivery fee cannot be negative")
            @DecimalMax(value = "10000.00", message = "Delivery fee looks too large. Is this a typo?")
            @Digits(integer = 8, fraction = 2, message = "Delivery fee must have at most 2 decimal places")
            BigDecimal deliveryFee
    ) {}

    /**
     * Vendor banking capture. Patterns are SA-shaped sanity rails, not bank
     * validation: branch codes are 6 digits universally; account numbers are
     * digits, length varying by bank. The bank itself is the only real
     * validator — these just stop obvious typos before they reach a bulk
     * payment file and bounce there.
     */
    public record BankingDetailsRequest(
            @NotBlank @Size(max = 120) String accountHolderName,
            @NotBlank @Size(max = 80)  String bankName,
            @NotBlank @Pattern(regexp = "\\d{6,20}", message = "Account number must be 6-20 digits")
            String accountNumber,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Branch code must be 6 digits")
            String branchCode,
            @NotNull BankAccountType accountType
    ) {}

    /**
     * The version echoes what the vendor was SHOWN — the server refuses a
     * stale one rather than record consent to text nobody rendered.
     */
    public record AcceptTermsRequest(@NotNull Integer version) {}
}
