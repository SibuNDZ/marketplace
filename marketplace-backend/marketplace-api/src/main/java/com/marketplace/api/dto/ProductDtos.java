package com.marketplace.api.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public class ProductDtos {

    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 64) String sku,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2)
            BigDecimal price,
            @NotNull @Min(0) Integer stock
    ) {}

    /**
     * Vendor is exposed as id + display name only — never the full User entity,
     * which would drag email and password hash into product listings.
     */
    public record ProductResponse(
            Long id,
            String name,
            String description,
            String sku,
            BigDecimal price,
            int stock,
            Long vendorId,
            String vendorName
    ) {}
}
