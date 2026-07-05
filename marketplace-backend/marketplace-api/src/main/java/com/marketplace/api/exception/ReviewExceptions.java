package com.marketplace.api.exception;

public class ReviewExceptions {

    /**
     * User is authenticated but has no DELIVERED order containing this product.
     * Maps to 403: it is a permission-shaped failure — the user lacks the
     * standing to review, and the message says exactly what earns that standing.
     */
    public static class NotVerifiedPurchaserException extends RuntimeException {
        public NotVerifiedPurchaserException(Long productId) {
            super("Reviews require a delivered order containing product " + productId);
        }
    }

    /** Maps to 409 — the review exists; the right verb is update, not create. */
    public static class DuplicateReviewException extends RuntimeException {
        public DuplicateReviewException(Long productId) {
            super("You have already reviewed product " + productId);
        }
    }

    public static class ReviewNotFoundException extends RuntimeException {
        public ReviewNotFoundException(Long id) {
            super("Review " + id + " not found");
        }
    }

    private ReviewExceptions() {}
}
