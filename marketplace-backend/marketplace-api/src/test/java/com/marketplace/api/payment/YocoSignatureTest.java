package com.marketplace.api.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Yoco/svix scheme. No Spring, no clock ambiguity — the instant is
 * injected, so staleness is tested without sleeping.
 *
 * ANTI-CIRCULARITY: the expected signature below is a hard-coded literal,
 * computed OUTSIDE this codebase from Yoco's documented recipe. It is
 * deliberately not produced by calling YocoSignature — a test that signs with
 * the same function it verifies with passes just as happily when both sides
 * are wrong the same way, which is exactly how the Stripe bug hid.
 *
 * Recipe, from developer.yoco.com/docs/api/webhooks/verifying-events:
 *   signed content = "{webhook-id}.{webhook-timestamp}.{raw-body}"
 *   key            = base64_decode(secret minus the "whsec_" prefix)
 *   signature      = base64(HMAC-SHA256(key, signed content))
 *   header entry   = "v1,<signature>"
 */
class YocoSignatureTest {

    /** Decodes to the 32 ASCII bytes "yoco-test-webhook-secret-32bytes". */
    static final String SECRET = "whsec_eW9jby10ZXN0LXdlYmhvb2stc2VjcmV0LTMyYnl0ZXM=";

    static final String WEBHOOK_ID = "msg_2aBcDeFgHiJkLmNoPqRsTuV";
    static final String TIMESTAMP = "1754218800";

    /** Real-shaped payment.succeeded, exactly as Yoco's OpenAPI describes it. */
    static final String BODY = "{\"id\":\"evt_01J8YOCOEVENT\",\"type\":\"payment.succeeded\","
            + "\"createdDate\":\"2026-08-03T09:00:00.000Z\",\"payload\":{\"id\":\"p_01J8PAYMENT\","
            + "\"type\":\"payment\",\"status\":\"succeeded\",\"amount\":510500,\"currency\":\"ZAR\","
            + "\"mode\":\"test\",\"metadata\":{\"orderId\":\"11\","
            + "\"orderNumber\":\"ORD-746A1D7B05434726\"}}}";

    /** Computed by hand from the recipe above. Do not regenerate with our code. */
    static final String VALID_HEADER = "v1,pTe4Jp9JKpkSyLSu241qb0YmOJpB7iyfW1wbeCYcZV4=";

    /** The moment the fixture was "signed" — verification runs against this. */
    static final Instant AT_SIGNING = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP));

    private static final Duration TOLERANCE = Duration.ofMinutes(3);

    private boolean verify(String id, String ts, String body, String header, Instant now) {
        return YocoSignature.verify(id, ts, body, header, SECRET, TOLERANCE, now);
    }

    @Test
    void validPayload_accepted() {
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, VALID_HEADER, AT_SIGNING)).isTrue();
    }

    @Test
    void tamperedBody_rejected() {
        // One digit of the amount changed: the classic replay-with-edit.
        String tampered = BODY.replace("510500", "100");
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, tampered, VALID_HEADER, AT_SIGNING)).isFalse();
    }

    @Test
    void tamperedSignature_rejected() {
        String corrupt = VALID_HEADER.substring(0, VALID_HEADER.length() - 5) + "AAAA=";
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, corrupt, AT_SIGNING)).isFalse();
    }

    @Test
    void tamperedWebhookId_rejected() {
        // The id is part of the signed content, so swapping it must break the
        // signature — this is what stops one delivery being replayed as another.
        assertThat(verify("msg_somethingElse", TIMESTAMP, BODY, VALID_HEADER, AT_SIGNING)).isFalse();
    }

    @Test
    void staleTimestamp_rejected() {
        Instant fourMinutesLater = AT_SIGNING.plus(Duration.ofMinutes(4));
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, VALID_HEADER, fourMinutesLater)).isFalse();
    }

    @Test
    void withinTolerance_accepted() {
        Instant twoMinutesLater = AT_SIGNING.plus(Duration.ofMinutes(2));
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, VALID_HEADER, twoMinutesLater)).isTrue();
    }

    @Test
    void farFutureTimestamp_rejected() {
        // Skew in the other direction is just as much a forgery signal.
        Instant fourMinutesEarlier = AT_SIGNING.minus(Duration.ofMinutes(4));
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, VALID_HEADER, fourMinutesEarlier)).isFalse();
    }

    @Test
    void nonNumericTimestamp_rejectedNotThrown() {
        assertThat(verify(WEBHOOK_ID, "not-a-timestamp", BODY, VALID_HEADER, AT_SIGNING)).isFalse();
    }

    @Test
    void missingHeaders_rejected() {
        assertThat(verify(null, TIMESTAMP, BODY, VALID_HEADER, AT_SIGNING)).isFalse();
        assertThat(verify(WEBHOOK_ID, null, BODY, VALID_HEADER, AT_SIGNING)).isFalse();
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, null, AT_SIGNING)).isFalse();
    }

    @Test
    void multipleSignatures_acceptedIfAnyMatches() {
        // svix sends old and new signatures side by side during a secret
        // rotation. Matching only the first entry would break at rotation.
        String header = "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= " + VALID_HEADER;
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, header, AT_SIGNING)).isTrue();
    }

    @Test
    void unknownVersionPrefix_ignoredNotFatal() {
        String header = "v2,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= " + VALID_HEADER;
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, header, AT_SIGNING)).isTrue();
    }

    @Test
    void malformedBase64Entry_doesNotMaskValidSibling() {
        String header = "v1,!!!not-base64!!! " + VALID_HEADER;
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, header, AT_SIGNING)).isTrue();
    }

    @Test
    void signatureOverRawSecretBytes_rejected() {
        // THE edge that silently produces a wrong-but-stable signature: using
        // the whole "whsec_..." string as UTF-8 key bytes instead of
        // base64-decoding the part after the prefix. Signing that way must not
        // verify, or we would ship a scheme that only fails against real
        // deliveries.
        String wrong = "v1," + hmacBase64(SECRET.getBytes(StandardCharsets.UTF_8),
                WEBHOOK_ID + "." + TIMESTAMP + "." + BODY);
        assertThat(verify(WEBHOOK_ID, TIMESTAMP, BODY, wrong, AT_SIGNING)).isFalse();
    }

    @Test
    void decodeSecret_stripsPrefixAndBase64Decodes() {
        assertThat(new String(YocoSignature.decodeSecret(SECRET), StandardCharsets.UTF_8))
                .isEqualTo("yoco-test-webhook-secret-32bytes");
    }

    /** Local HMAC, written from the doc — not YocoSignature's implementation. */
    private static String hmacBase64(byte[] key, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
