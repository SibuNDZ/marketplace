package com.marketplace.api.payment;

/**
 * Typed outcomes of a {@link PaystackClient} call, so the caller decides
 * what an outage or a rejection means for ITS transaction (fault isolation,
 * ROADMAP): a vendor saving banking must never be blocked by a Paystack
 * outage, while an admin approving a vendor must be, because approval
 * without a subaccount would be a half-approved vendor.
 */
public final class PaystackExceptions {

    /**
     * Paystack could not be reached, timed out, or answered 5xx. Extends the
     * provider-unavailable type so an uncaught one maps to the existing 502
     * problem shape ({@code payments:provider-unavailable}).
     */
    public static class PaystackUnavailableException
            extends PaymentExceptions.PaymentProviderUnavailableException {
        public PaystackUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Paystack answered 4xx: it understood the request and refused it. The
     * message is Paystack's own {@code message} field, which names the
     * rejected parameter and never echoes an account or document number.
     */
    public static class PaystackRejectedException extends RuntimeException {
        private final int status;

        public PaystackRejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private PaystackExceptions() {}
}
