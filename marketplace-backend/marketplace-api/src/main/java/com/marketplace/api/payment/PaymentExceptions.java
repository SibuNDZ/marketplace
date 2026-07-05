package com.marketplace.api.payment;

public class PaymentExceptions {

    /** Stripe API failure — maps to 502 Bad Gateway (upstream provider failed). */
    public static class PaymentProviderException extends RuntimeException {
        public PaymentProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private PaymentExceptions() {}
}
