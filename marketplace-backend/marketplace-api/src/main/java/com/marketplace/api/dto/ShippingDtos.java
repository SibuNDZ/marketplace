package com.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ShippingDtos {

    /**
     * Submitted once, at pay-time, via PaymentController.pay — NOT a new
     * controller, NOT Stripe's native shipping_address_collection (see
     * rationale in integration notes: the webhook parser was JUST
     * stabilized against API-version drift by reading raw JSON; routing
     * a second field through that same fragile surface right after
     * fixing it is how a closed bug reopens).
     */
    public record ShippingAddressRequest(
            @NotBlank @Size(max = 200) String recipientName,
            @NotBlank @Size(max = 30)
            @Pattern(regexp = "^[0-9+()\\-\\s]{7,30}$", message = "Enter a valid phone number")
            String phone,
            @NotBlank @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,   // optional: unit/complex/floor
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String province,
            @NotBlank @Size(max = 20) String postalCode
    ) {}

    /**
     * The masked shape — what an ADMIN sees on a not-yet-PAID order.
     * Deliberately a DIFFERENT record from the full address, not the same
     * record with null fields: the type system then makes "did I remember
     * to mask this" a compile-time question at every call site, not a
     * runtime one.
     */
    // "Not yet available" is represented as a plain null of this type on
    // OrderResponse — same convention ProductResponse already uses for
    // imageUrl ("not yet present" = null, not a sentinel object). No new
    // convention introduced by this slice.
    public record ShippingAddressResponse(
            String recipientName, String phone, String addressLine1,
            String addressLine2, String city, String province, String postalCode
    ) {}
}
