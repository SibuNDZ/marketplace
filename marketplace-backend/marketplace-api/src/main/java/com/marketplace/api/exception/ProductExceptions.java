package com.marketplace.api.exception;

public class ProductExceptions {

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(Long id) {
            super("Product " + id + " not found");
        }
    }

    /**
     * Live-SKU collision (uq_products_sku_live is a partial index — a deleted
     * product's SKU stays reusable). Mapped to 409 in GlobalExceptionHandler.
     */
    public static class DuplicateSkuException extends RuntimeException {
        public DuplicateSkuException(String sku) {
            super("SKU already in use: " + sku);
        }
    }

    /**
     * Compare-at pricing is paused — see ProductService.applyRequest.
     * Mapped to 400 in GlobalExceptionHandler.
     */
    public static class CompareAtPricingPausedException extends RuntimeException {
        public CompareAtPricingPausedException() {
            super("Compare-at pricing is temporarily unavailable while we build a verified-history "
                    + "version. Your other changes were not saved — remove the original price and try again.");
        }
    }

    private ProductExceptions() {}
}
