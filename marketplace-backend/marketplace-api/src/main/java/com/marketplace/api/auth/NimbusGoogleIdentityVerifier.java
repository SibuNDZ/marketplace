package com.marketplace.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production Google ID-token verification against Google's published JWKS.
 *
 * The decoder is built lazily on first use, not at startup: the JWKS fetch is
 * a network call, and boot (and the test context) must not depend on Google
 * being reachable. Nimbus caches the key set and refreshes it on unknown-kid,
 * so steady-state verification does not re-fetch per request.
 */
@Component
public class NimbusGoogleIdentityVerifier implements GoogleIdentityVerifier {

    /** Static well-known JWKS URI — stable, documented, no OIDC discovery needed. */
    static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    private final String clientId;
    private volatile NimbusJwtDecoder decoder;

    public NimbusGoogleIdentityVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    @Override
    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    @Override
    public GoogleIdentity verify(String credential) {
        if (!isConfigured()) {
            throw new IllegalStateException("Google sign-in is not configured");
        }
        try {
            Jwt jwt = decoder().decode(credential);
            return new GoogleIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                    jwt.getClaimAsString("given_name"),
                    jwt.getClaimAsString("family_name"));
        } catch (JwtException e) {
            // One generic failure for bad signature, expiry, wrong audience,
            // wrong issuer alike. The message is shown to the user, so it
            // says what to do, not what failed.
            throw new InvalidGoogleCredentialException(
                    "Google sign-in could not be verified. Please try again.", e);
        }
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                local = decoder;
                if (local == null) {
                    local = buildDecoder();
                    decoder = local;
                }
            }
        }
        return local;
    }

    private NimbusJwtDecoder buildDecoder() {
        NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build();
        // Google's documentation allows BOTH issuer forms, so a single-issuer
        // validator would intermittently reject valid tokens.
        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>("iss",
                iss -> "https://accounts.google.com".equals(iss) || "accounts.google.com".equals(iss));
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(clientId));
        built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), issuer, audience));
        return built;
    }
}
