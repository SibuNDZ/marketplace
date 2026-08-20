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
}
