package com.marketplace.api.exception;

public class ProductExceptions {

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(Long id) {
            super("Product " + id + " not found");
        }
    }

    private ProductExceptions() {}
}
