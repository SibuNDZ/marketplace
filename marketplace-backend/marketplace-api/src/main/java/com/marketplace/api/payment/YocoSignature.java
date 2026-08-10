package com.marketplace.api.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Yoco webhook signature verification. Yoco delivers through svix, so this is
 * the standard svix scheme — but every detail below was read off Yoco's own
 * docs (developer.yoco.com -> /docs/api/webhooks/verifying-events), not
 * assumed from svix in general, because the PayFast port taught us that a
 * provider's "standard" scheme has provider-specific edges:
 *
 *   headers        webhook-id, webhook-timestamp, webhook-signature
 *   signed content "{webhook-id}.{webhook-timestamp}.{raw-body}"   (full stops)
 *   secret         "whsec_" prefix STRIPPED, remainder BASE64-DECODED to key bytes
 *   mac            HMAC-SHA256 over the signed content, digest BASE64-ENCODED
 *   header value   one or more space-separated entries, each "v1,<base64sig>"
 *   tolerance      Yoco recommends a threshold of up to 3 MINUTES
 *
 * Two edges that are easy to get wrong and are pinned by YocoSignatureTest:
 *
 * 1. The secret is base64 AFTER the prefix. Feeding the whole "whsec_..."
 *    string to the Mac as UTF-8 bytes produces a stable, plausible, wrong
 *    signature that fails only against real deliveries.
 * 2. The header can carry SEVERAL signatures (svix sends the old and the new
 *    one across a secret rotation). Matching only the first entry breaks
 *    silently at rotation time, so every entry is checked.
 *
 * Comparison is constant-time via MessageDigest.isEqual.
 */
final class YocoSignature {

    /** Yoco's documented recommendation is "up to 3 minutes". */
    static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(3);

    private static final String PREFIX = "whsec_";
    private static final String VERSION = "v1,";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Full verification: signature first, then freshness. Returns false for
     * anything malformed — a caller must not have to distinguish "bad base64"
     * from "wrong key", both are simply "not from Yoco".
     */
    static boolean verify(String webhookId,
                          String webhookTimestamp,
                          String rawBody,
                          String signatureHeader,
                          String secret,
                          Duration tolerance,
                          Instant now) {
        if (webhookId == null || webhookTimestamp == null
                || rawBody == null || signatureHeader == null || secret == null) {
            return false;
        }
        if (!isFresh(webhookTimestamp, tolerance, now)) {
            return false;
        }
        byte[] expected;
        try {
            expected = expectedSignature(webhookId, webhookTimestamp, rawBody, secret);
        } catch (Exception e) {
            return false;
        }
        // Space-separated list; each entry "v1,<base64>". Unknown versions are
        // skipped rather than rejected, so a future v2 rollout degrades to
        // "v1 still matches" instead of hard-failing every delivery.
        for (String entry : signatureHeader.trim().split("\\s+")) {
            if (!entry.startsWith(VERSION)) {
                continue;
            }
            try {
                byte[] candidate = Base64.getDecoder().decode(entry.substring(VERSION.length()));
                if (MessageDigest.isEqual(expected, candidate)) {
                    return true;
                }
            } catch (IllegalArgumentException malformedBase64) {
                // Try the next entry: one corrupt signature must not mask a
                // valid sibling during rotation.
            }
        }
        return false;
    }

    /**
     * Replay defence. The timestamp is Unix SECONDS. Rejected in both
     * directions: a far-future timestamp is as much a forgery signal as a
     * stale one, and clock skew is what the tolerance is for.
     */
    static boolean isFresh(String webhookTimestamp, Duration tolerance, Instant now) {
        long seconds;
        try {
            seconds = Long.parseLong(webhookTimestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long skew = Math.abs(now.getEpochSecond() - seconds);
        return skew <= tolerance.toSeconds();
    }

    static byte[] expectedSignature(String webhookId,
                                    String webhookTimestamp,
                                    String rawBody,
                                    String secret) throws Exception {
        String signedContent = webhookId + "." + webhookTimestamp + "." + rawBody;
        byte[] key = decodeSecret(secret);
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
        return mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
    }

    /** "whsec_<base64>" -> raw key bytes. The prefix is NOT part of the key. */
    static byte[] decodeSecret(String secret) {
        String body = secret.startsWith(PREFIX) ? secret.substring(PREFIX.length()) : secret;
        return Base64.getDecoder().decode(body);
    }

    private YocoSignature() {}
}
