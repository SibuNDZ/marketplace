package com.marketplace.api.dto;

import com.marketplace.api.entity.ProductCategory;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ProductDtos {

    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 64) String sku,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2)
            BigDecimal price,
            @NotNull @Min(0) Integer stock,
            @NotNull ProductCategory category
    ) {}

    /**
     * Vendor is exposed as id + display name only — never the full User entity,
     * which would drag email and password hash into product listings.
     *
     * Signal fields come from the product_popularity read model (V9, rebuilt
     * hourly): zeros when a product has no row yet — a product created since
     * the last rebuild has no aggregates, and zeros are the truthful answer.
     * soldCount counts KEPT sales only (PAID/SHIPPED/DELIVERED; refunds
     * excluded — see PopularityJob). weighted_rating and views_30d stay
     * internal: one ranks, one is a raw behavioral count; neither is a
     * customer-facing fact. createdAt is LocalDateTime per BaseEntity.
     */
    public record ProductResponse(
            Long id,
            String name,
            String description,
            String sku,
            BigDecimal price,
            int stock,
            Long vendorId,
            String vendorName,
            BigDecimal avgRating,   // 0 when unreviewed
            long reviewCount,       // 0 when unreviewed
            long soldCount,         // kept sales only
            LocalDateTime createdAt, // real recency — feeds the honest "New in" chip
            ProductCategory category
    ) {}

    /** GET /api/v1/products/categories — live-product counts per category, for the sidebar. */
    public record CategoryCount(ProductCategory category, long count) {}
}
