package com.marketplace.api.auth;

/**
 * Verifies a Google ID token (the GIS "credential") and returns its claims.
 *
 * This is a seam, same pattern as PayfastValidator: the production
 * implementation talks to Google's JWKS; tests inject a stub with canned
 * identities, so CI never depends on Google being reachable.
 */
public interface GoogleIdentityVerifier {

    /**
     * The subset of ID-token claims the account model needs. {@code sub} is
     * Google's stable subject ID and the only durable key; everything else
     * is display material or (emailVerified) a linking precondition.
     */
    record GoogleIdentity(String sub,
                          String email,
                          boolean emailVerified,
                          String givenName,
                          String familyName) {}

    /** False when GOOGLE_CLIENT_ID is not configured — the feature is dark. */
    boolean isConfigured();

    /**
     * Verify signature, issuer, audience, and expiry.
     *
     * @throws InvalidGoogleCredentialException for any invalid, expired, or
     *         wrong-audience credential — one exception for every technical
     *         failure mode. Distinct from BadCredentialsException on purpose:
     *         that handler's fixed "Invalid email or password" text is
     *         deliberate anti-enumeration for login, but on the Google path
     *         it is simply the wrong sentence, and there is nothing to
     *         enumerate in a signature failure.
     * @throws IllegalStateException if called while not configured.
     */
    GoogleIdentity verify(String credential);

    /** 401 with its own message — see {@link #verify}. */
    class InvalidGoogleCredentialException extends RuntimeException {
        public InvalidGoogleCredentialException(String message) {
            super(message);
        }

        public InvalidGoogleCredentialException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
