package com.marketplace.api.service;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * The rules about what a purchasable line IS, in one place.
 *
 * Stock and price live in two places depending on whether a product has
 * options: on the product itself, or on the chosen variant. Every code path
 * that adds to a cart, prices an order, decrements stock or restores it has
 * to answer the same question the same way, and answering it differently in
 * two of them is how a marketplace oversells or charges the wrong amount.
 *
 * So the decision is made here and nowhere else.
 */
public final class VariantSelection {

    private VariantSelection() {}

    /**
     * A product with options CANNOT be bought without choosing one.
     *
     * Before V25 the cart took a productId only, so a variant product was
     * added with no option at all — the buyer got "the product", priced from
     * products.price, while the price shown to them was the cheapest option
     * and the stock they saw was the sum of the options. Three different
     * numbers for one purchase.
     */
    public static void validate(Product product,
                                @Nullable ProductVariant variant,
                                List<ProductVariant> productVariants) {
        boolean hasOptions = !productVariants.isEmpty();

        if (hasOptions && variant == null) {
            throw new VariantRequiredException(product.getName());
        }
        if (!hasOptions && variant != null) {
            throw new VariantNotApplicableException(product.getName());
        }
        // Belongs-to check. Without it a caller could pass any variant id in
        // the system and buy another product's option at that option's price.
        if (variant != null && !variant.getProduct().getId().equals(product.getId())) {
            throw new VariantNotApplicableException(product.getName());
        }
    }

    /** What the buyer pays per unit: the option's price when there is one. */
    public static BigDecimal priceOf(Product product, @Nullable ProductVariant variant) {
        return variant != null ? variant.getPrice() : product.getPrice();
    }

    /** What is actually decremented: the option's stock when there is one. */
    public static int stockOf(Product product, @Nullable ProductVariant variant) {
        return variant != null ? variant.getStockQuantity() : product.getStock();
    }

    /** The counterpart to stockOf — writes to whichever side owns the count. */
    public static void setStock(Product product, @Nullable ProductVariant variant, int value) {
        if (variant != null) variant.setStockQuantity(value);
        else product.setStock(value);
    }

    /** Display name for shortages and receipts: "Watch set — Large". */
    public static String describe(Product product, @Nullable ProductVariant variant) {
        return variant == null ? product.getName()
                : product.getName() + " — " + variant.getLabel();
    }

    /** Maps to 400. */
    public static class VariantRequiredException extends RuntimeException {
        public VariantRequiredException(String productName) {
            super("Choose an option for " + productName + " before adding it to your cart");
        }
    }

    /** Maps to 400. */
    public static class VariantNotApplicableException extends RuntimeException {
        public VariantNotApplicableException(String productName) {
            super("That option does not belong to " + productName);
        }
    }
}
