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

    private ProductExceptions() {}
}
