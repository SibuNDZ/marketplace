package com.marketplace.api.ai;

public class DraftExceptions {

    /**
     * The drafting provider failed, or returned something we cannot use.
     *
     * Maps to 502, following the PaymentProviderException precedent: the
     * vendor's request was well-formed, an upstream we depend on did not hold
     * up its end. 500 would imply our bug; 400 would blame the vendor for a
     * photo that was perfectly fine.
     *
     * The detail returned to the client is deliberately generic — the raw
     * model output goes to the log (truncated), never to the browser, because
     * it is unvalidated text from a third party.
     */
    public static class DraftProviderException extends RuntimeException {
        public DraftProviderException(String message) {
            super(message);
        }

        public DraftProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The per-vendor hourly budget for drafting is spent.
     *
     * Separate from the auth limiter's 429 because the cause and the remedy
     * differ: that one is "you are guessing passwords", this one is "this
     * endpoint costs real money per call and you have had your hour's worth".
     * Carries its own retry window so the response can set Retry-After.
     */
    public static class DraftRateLimitExceededException extends RuntimeException {
        private final long retryAfterSeconds;

        public DraftRateLimitExceededException(long retryAfterSeconds) {
            super("Draft rate limit exceeded; retry in " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
