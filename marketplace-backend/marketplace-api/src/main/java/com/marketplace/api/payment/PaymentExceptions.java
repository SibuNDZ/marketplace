package com.marketplace.api.payment;

import com.stripe.exception.AuthenticationException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.StripeException;

public class PaymentExceptions {

    /**
     * RFC 7807 {@code type} values. Shopper-facing title/detail stay generic;
     * ops distinguish key mistakes from outages via this URI (and {@code code}).
     */
    public static final String TYPE_MISCONFIGURED =
            "https://erestyu.com/problems/payments:provider-misconfigured";
    public static final String TYPE_UNAVAILABLE =
            "https://erestyu.com/problems/payments:provider-unavailable";
    public static final String CODE_MISCONFIGURED = "payments:provider-misconfigured";
    public static final String CODE_UNAVAILABLE = "payments:provider-unavailable";

    /** Stripe/Yoco/PayFast API failure — maps to 502 Bad Gateway. */
    public static class PaymentProviderException extends RuntimeException {
        public PaymentProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Keys, mode, or credentials are wrong — not an upstream outage. */
    public static class PaymentProviderMisconfiguredException extends PaymentProviderException {
        public PaymentProviderMisconfiguredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Provider was reachable-as-configured but failed or timed out. */
    public static class PaymentProviderUnavailableException extends PaymentProviderException {
        public PaymentProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Classify a Stripe SDK failure: auth/permission means our keys or mode
     * are wrong; everything else is treated as an outage (including ambiguous
     * InvalidRequestException, which is often our payload, not the key).
     */
    public static PaymentProviderException fromStripe(String message, StripeException cause) {
        if (cause instanceof AuthenticationException || cause instanceof PermissionException) {
            return new PaymentProviderMisconfiguredException(message, cause);
        }
        return new PaymentProviderUnavailableException(message, cause);
    }

    /**
     * Classify a Yoco HTTP status. 401/403 (and 400 on the checkout create
     * we control) are configuration; 5xx and anything else is an outage.
     */
    public static PaymentProviderException fromHttpStatus(String message, int status) {
        if (status == 400 || status == 401 || status == 403) {
            return new PaymentProviderMisconfiguredException(message, null);
        }
        return new PaymentProviderUnavailableException(message, null);
    }

    public static PaymentProviderException unavailable(String message, Throwable cause) {
        return new PaymentProviderUnavailableException(message, cause);
    }

    public static PaymentProviderException misconfigured(String message, Throwable cause) {
        return new PaymentProviderMisconfiguredException(message, cause);
    }

    private PaymentExceptions() {}
}
