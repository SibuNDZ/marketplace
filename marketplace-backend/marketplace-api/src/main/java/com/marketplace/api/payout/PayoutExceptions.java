package com.marketplace.api.payout;

/**
 * Payout domain exceptions, translated to RFC 7807 by GlobalExceptionHandler
 * (409 for state conflicts, 404 for absent batches — the same status logic
 * as the order exceptions).
 */
public class PayoutExceptions {

    public static class PayoutBatchNotFoundException extends RuntimeException {
        public PayoutBatchNotFoundException(Long batchId) {
            super("Payout batch " + batchId + " not found");
        }
    }

    /** An approve/export/paid step attempted out of order or twice. */
    public static class InvalidPayoutStateException extends RuntimeException {
        public InvalidPayoutStateException(String message) {
            super(message);
        }
    }

    /**
     * A vendor in the selection cannot be paid: incomplete banking details,
     * or a non-positive sum (a bank cannot transfer a negative amount — the
     * adjustment carries forward until future entries outweigh it).
     */
    public static class VendorNotPayableException extends RuntimeException {
        public VendorNotPayableException(String message) {
            super(message);
        }
    }

    /**
     * The selling gate, from the shopper's side: the cart holds items from a
     * vendor who has not completed payout onboarding (terms + banking).
     * Carries the affected vendor/product names so checkout can say WHICH
     * items to remove — the InsufficientStock shape, because from the
     * shopper's seat this is the same event: an item that cannot currently
     * be bought.
     */
    public static class VendorNotSellableException extends RuntimeException {
        public record BlockedItem(String vendorName, String productName) {}

        private final java.util.List<BlockedItem> blocked;

        public VendorNotSellableException(java.util.List<BlockedItem> blocked) {
            super("Some items are temporarily unavailable while their vendor completes payout setup: "
                    + blocked.stream()
                            .map(b -> b.productName() + " (" + b.vendorName() + ")")
                            .distinct()
                            .collect(java.util.stream.Collectors.joining(", ")));
            this.blocked = java.util.List.copyOf(blocked);
        }

        public java.util.List<BlockedItem> getBlocked() {
            return blocked;
        }
    }

    /** An admin decision on a vendor who has never submitted banking: nothing to decide. */
    public static class VerificationNotFoundException extends RuntimeException {
        public VerificationNotFoundException(Long vendorId) {
            super("Vendor " + vendorId + " has no verification on file");
        }
    }

    /**
     * Approve refused because the provider's result forbids it (MISMATCH,
     * UNAVAILABLE). There is no "approve anyway": the admin retries the
     * validation or rejects (paystack-subaccounts.md §4.3).
     */
    public static class VerificationNotApprovableException extends RuntimeException {
        public VerificationNotApprovableException(Long vendorId,
                                                  com.marketplace.api.entity.VerificationProviderResult result) {
            super("Vendor " + vendorId + " cannot be approved: provider result is " + result
                    + ". Re-run the validation or reject.");
        }
    }

    /** An admin re-run of the provider check that cannot proceed: already decided, or Paystack refused it. */
    public static class VerificationRecheckRefusedException extends RuntimeException {
        public VerificationRecheckRefusedException(String message) {
            super(message);
        }
    }

    /** Terms acceptance raced a version bump; the client must re-show the new text. */
    public static class StaleTermsVersionException extends RuntimeException {
        public StaleTermsVersionException(int sent, int current) {
            super("Payout terms have changed (you accepted version " + sent
                    + ", current is " + current + "); please review and accept the new terms");
        }
    }
}
