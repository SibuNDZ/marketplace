package com.marketplace.api.exception;

import java.util.List;

/**
 * Business rule violations for the order domain. Runtime exceptions so
 * @Transactional rolls back on failure — no partial stock decrements,
 * no half-created orders.
 */
public class OrderExceptions {

    public static class EmptyCartException extends RuntimeException {
        public EmptyCartException() {
            super("Cannot place an order: cart is empty");
        }
    }

    public static class CartNotFoundException extends RuntimeException {
        public CartNotFoundException(Long userId) {
            super("No cart found for user " + userId);
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(Long orderId) {
            super("Order " + orderId + " not found");
        }
    }

    public static class InvalidOrderStateException extends RuntimeException {
        public InvalidOrderStateException(String message) {
            super(message);
        }
    }

    /**
     * Carries per-item shortages so the API reports all failures at once,
     * not one per retry.
     */
    public static class InsufficientStockException extends RuntimeException {
        private final List<StockShortage> shortages;

        public InsufficientStockException(List<StockShortage> shortages) {
            super("Insufficient stock for " + shortages.size() + " item(s)");
            this.shortages = List.copyOf(shortages);
        }

        public List<StockShortage> getShortages() {
            return shortages;
        }

        public record StockShortage(Long productId, String productName, int requested, int available) {}
    }

    private OrderExceptions() {}
}
