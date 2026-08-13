package com.marketplace.api.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ProductDtos {

    /**
     * categorySlug rather than a category id: the vendor form posts back
     * whatever the picker gave it, and a slug survives a reseed while an
     * id does not. Unknown slugs are a 404 from CategoryService, not a
     * silent fallback to Other — a product filed somewhere the vendor did
     * not choose is worse than a rejected save.
     */
    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 64) String sku,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2)
            BigDecimal price,
            /**
             * The "was" price. Null or omitted means not on sale, which is
             * the default and the state of every existing listing.
             *
             * Must exceed price — see discountIsReal below. That is checked
             * here AND by a CHECK constraint in V23, deliberately: the
             * message here is what a vendor reads, the constraint is what
             * makes it impossible for any other write path to get around.
             */
            @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2)
            BigDecimal originalPrice,
            @NotNull @Min(0) Integer stock,
            @NotBlank String categorySlug,
            Boolean handmade,
            /**
             * Capped at 10, each 30 chars. Not a schema limit — TEXT[] would
             * take a thousand — but a tag list long enough to be a keyword
             * dump stops being a useful filter and starts being SEO spam.
             */
            @Size(max = 10, message = "At most 10 tags")
            List<@NotBlank @Size(max = 30) String> tags
    ) {
        /**
         * A "was" price that is not above the selling price is not a
         * discount. Rejecting it stops the two ways this goes wrong: equal
         * prices render "0% off", and a lower one renders a negative
         * discount, both of which look like a bug and read as a trick.
         *
         * @AssertTrue puts this in the same 400 response as every other
         * field error, so the vendor form can show it inline rather than
         * surfacing a 500 from the database constraint underneath.
         */
        @jakarta.validation.constraints.AssertTrue(
                message = "The original price must be higher than the selling price")
        public boolean isDiscountIsReal() {
            if (originalPrice == null || price == null) return true;
            return originalPrice.compareTo(price) > 0;
        }

        public boolean handmadeOrFalse() {
            return Boolean.TRUE.equals(handmade);
        }

        public List<String> tagsOrEmpty() {
            return tags == null ? List.of() : tags;
        }
    }

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
     *
     * Category is returned as slug + name + parent so a product card can
     * render "Fashion / Jewellery" without a second lookup.
     */
    public record ProductResponse(
            Long id,
            String name,
            String description,
            String sku,
            BigDecimal price,
            /**
             * The "was" price, or null when not on sale — which is almost
             * always. The frontend renders a strikethrough and a percentage
             * ONLY when this is present, so an absent value can never
             * produce a discount badge.
             */
            BigDecimal originalPrice,
            int stock,
            Long vendorId,
            String vendorName,
            BigDecimal avgRating,   // 0 when unreviewed
            long reviewCount,       // 0 when unreviewed
            long soldCount,         // kept sales only
            LocalDateTime createdAt, // real recency — feeds the honest "New in" chip
            String categorySlug,
            String categoryName,
            String parentCategorySlug, // null when the product sits on a top-level category
            boolean handmade,
            List<String> tags,
            String imageUrl,         // null until a vendor uploads one — frontend falls back to a placeholder
            /**
             * Soft-delete timestamp. Always null on public catalog responses
             * (those queries filter deleted rows out); non-null only on the
             * vendor's own /mine listing, where it drives the Archived tab.
             */
            LocalDateTime deletedAt,
            /**
             * Purchasable options (V20). EMPTY for most products, and empty
             * means "buy the product itself" — today's behaviour.
             *
             * When NON-empty the variants are the only place stock and price
             * live: `stock` above is their summed stock and `price` is the
             * cheapest variant, so a card can honestly say "from R120".
             */
            List<VariantResponse> variants,
            /**
             * Why this product appears on a related-items shelf. Null on every
             * other endpoint, in the same spirit as deletedAt above: the field
             * is meaningful on exactly one path and honest about it elsewhere.
             *
             * Two values, both literally true — "Similar item" for an
             * embedding match, "Shares keywords" for a text-only one. It is
             * also the only way to tell from outside the app whether a shelf
             * came from embeddings or fell back to text, which previously
             * needed a database query to answer.
             */
            String similarityReason
    ) {
        /** Records have no withers, and the alternative is repeating twenty
         *  fields at the one call site that needs this. */
        public ProductResponse withSimilarityReason(String reason) {
            return new ProductResponse(id, name, description, sku, price, originalPrice,
                    stock, vendorId, vendorName, avgRating, reviewCount, soldCount,
                    createdAt, categorySlug, categoryName, parentCategorySlug,
                    handmade, tags, imageUrl, deletedAt, variants, reason);
        }
    }

    /** One purchasable option. Price is absolute, never a delta. */
    public record VariantResponse(
            Long id,
            String label,
            String sku,
            BigDecimal price,
            int stock,
            String imageUrl
    ) {}

    public record VariantRequest(
            @NotBlank @Size(max = 100) String label,
            @Size(max = 100) String sku,
            @NotNull @DecimalMin(value = "0.01", message = "Price must be greater than 0")
            @Digits(integer = 8, fraction = 2) BigDecimal price,
            @NotNull @Min(0) @Max(1_000_000) Integer stock,
            Integer position
    ) {}
}
