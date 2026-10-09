package com.marketplace.api.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Paystack webhook authenticity: the x-paystack-signature header is the
 * lowercase hex HMAC-SHA512 of the raw request body, keyed with the account's
 * SECRET KEY (there is no separate webhook secret, unlike Stripe and Yoco).
 * Consequence worth knowing: rotating the secret key also rotates webhook
 * verification, and a test key can never verify a live delivery.
 *
 * No timestamp is signed, so there is no replay window to enforce here.
 * Replays are harmless downstream: the state machine is idempotent on PAID.
 */
final class PaystackSignature {

    static boolean verify(String rawBody, String signatureHeader, String secretKey) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody, secretKey).getBytes(StandardCharsets.US_ASCII);
        byte[] received = signatureHeader.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }

    static String sign(String rawBody, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA512 unavailable", e);
        }
    }

    private PaystackSignature() {}
}
