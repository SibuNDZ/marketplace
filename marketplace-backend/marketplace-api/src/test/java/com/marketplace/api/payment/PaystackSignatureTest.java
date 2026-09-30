package com.marketplace.api.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expected signatures come from a local HMAC written from Paystack's doc, not
 * from PaystackSignature.sign(), so a bug in the class cannot vouch for itself.
 */
class PaystackSignatureTest {

    static final String KEY = "sk_test_paystack_placeholder_for_tests";
    static final String BODY = "{\"event\":\"charge.success\",\"data\":{\"amount\":15000}}";

    static String docHmac(String body, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validSignature_verifies() throws Exception {
        assertThat(PaystackSignature.verify(BODY, docHmac(BODY, KEY), KEY)).isTrue();
    }

    @Test
    void uppercaseHexAndWhitespace_stillVerify() throws Exception {
        assertThat(PaystackSignature.verify(BODY, " " + docHmac(BODY, KEY).toUpperCase() + "\n", KEY)).isTrue();
    }

    @Test
    void editedBody_fails() throws Exception {
        assertThat(PaystackSignature.verify(BODY.replace("15000", "1"), docHmac(BODY, KEY), KEY)).isFalse();
    }

    @Test
    void wrongKey_fails() throws Exception {
        // A test key must never verify a live delivery, and vice versa.
        assertThat(PaystackSignature.verify(BODY, docHmac(BODY, "sk_live_other"), KEY)).isFalse();
    }

    @Test
    void missingHeaderOrBlankKey_fails() throws Exception {
        assertThat(PaystackSignature.verify(BODY, null, KEY)).isFalse();
        assertThat(PaystackSignature.verify(BODY, "", KEY)).isFalse();
        assertThat(PaystackSignature.verify(BODY, docHmac(BODY, KEY), "")).isFalse();
    }

    @Test
    void nonAsciiBody_signsAsUtf8() throws Exception {
        String body = "{\"data\":{\"metadata\":{\"note\":\"Gqeberha café – R150\"}}}";
        assertThat(PaystackSignature.verify(body, docHmac(body, KEY), KEY)).isTrue();
    }
}
