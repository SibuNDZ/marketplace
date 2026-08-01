package com.marketplace.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

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
}
