package com.marketplace.api.payment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the two signature regimes. The asymmetries under test
 * (ordering source, blank handling) are PayFast's top documented integration
 * failures, so each one is pinned explicitly.
 */
class PayfastSignatureTest {

    @Test
    void requestSignature_usesInsertionOrder_skipsBlanks_appendsPassphrase() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("merchant_id", "10000100");
        fields.put("merchant_key", "46f0cd694581a");
        fields.put("return_url", "https://example.com/success");
        fields.put("name_first", "");             // blank: must be skipped
        fields.put("m_payment_id", "42");
        fields.put("amount", "100.00");
        fields.put("item_name", "Test Item");

        String signature = PayfastSignature.sign(fields, "secret-phrase");

        // Independently computed over the same rules: insertion order, blank
        // skipped, uppercase percent-encoding, spaces as '+', passphrase last.
        String expectedInput = "merchant_id=10000100&merchant_key=46f0cd694581a"
                + "&return_url=https%3A%2F%2Fexample.com%2Fsuccess"
                + "&m_payment_id=42&amount=100.00&item_name=Test+Item"
                + "&passphrase=secret-phrase";
        assertThat(signature).isEqualTo(md5(expectedInput)).hasSize(32).isLowerCase();
    }

    @Test
    void requestSignature_withoutPassphrase_omitsThePair() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("merchant_id", "10000100");
        fields.put("amount", "50.00");
        assertThat(PayfastSignature.sign(fields, ""))
                .isEqualTo(md5("merchant_id=10000100&amount=50.00"));
    }

    @Test
    void encoding_isUppercaseHex_withPlusForSpaces() {
        assertThat(PayfastSignature.encode("http://a b/c?x=1"))
                .isEqualTo("http%3A%2F%2Fa+b%2Fc%3Fx%3D1");
    }

    @Test
    void itnVerification_usesReceivedOrder_includesBlanks_stopsAtSignature() {
        // Payload deliberately NOT in documented order, with a blank field:
        // verification must follow the wire order and keep the blank.
        String paramString = "pf_payment_id=1089250&m_payment_id=42&custom_str1="
                + "&payment_status=COMPLETE&amount_gross=100.00";
        String signature = md5(paramString + "&passphrase=secret-phrase");
        String rawBody = paramString + "&signature=" + signature;

        List<Map.Entry<String, String>> ordered = PayfastSignature.parseOrdered(rawBody);
        assertThat(PayfastSignature.verifyItn(ordered, signature, "secret-phrase")).isTrue();
        assertThat(PayfastSignature.verifyItn(ordered, md5("tampered"), "secret-phrase")).isFalse();
        assertThat(PayfastSignature.verifyItn(ordered, signature, "wrong-phrase")).isFalse();
        assertThat(PayfastSignature.verifyItn(ordered, null, "secret-phrase")).isFalse();
    }

    @Test
    void parseOrdered_preservesWireOrder_andDecodes() {
        List<Map.Entry<String, String>> params =
                PayfastSignature.parseOrdered("b=2&a=with+space%3A1&empty=");
        assertThat(params).extracting(Map.Entry::getKey).containsExactly("b", "a", "empty");
        assertThat(params.get(1).getValue()).isEqualTo("with space:1");
        assertThat(params.get(2).getValue()).isEmpty();
    }

    private static String md5(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            return java.util.HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
