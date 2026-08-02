package com.marketplace.api.feedback;

public final class FeedbackExceptions {

    private FeedbackExceptions() {}

    /**
     * Same shape as the drafter's 429 (real Retry-After window), separate
     * class because the remedy copy differs: this endpoint costs nothing,
     * the limit only exists to stop spam, and a sincere user should be told
     * their feedback can wait a few minutes, not that they spent a budget.
     */
    public static class FeedbackRateLimitExceededException extends RuntimeException {
        private final long retryAfterSeconds;

        public FeedbackRateLimitExceededException(long retryAfterSeconds) {
            super("Feedback rate limit exceeded; retry in " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
