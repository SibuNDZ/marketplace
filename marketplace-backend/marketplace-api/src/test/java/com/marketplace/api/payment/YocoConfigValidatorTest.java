package com.marketplace.api.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider-flag contract, as a plain unit test — no 40-second context boot
 * needed to pin a rule this small.
 *
 * The rule: missing Yoco keys are a BOOT FAILURE when yoco is the selected
 * provider, and a complete non-event otherwise. Both halves matter. Without
 * the first, a yoco deploy boots healthy and 502s every checkout; without the
 * second, adding these properties would break every existing Stripe deploy
 * that has no YOCO_* vars set at all.
 */
class YocoConfigValidatorTest {

    private static void validate(String provider, String secretKey, String webhookSecret) {
        new YocoConfigValidator(provider, secretKey, webhookSecret).afterPropertiesSet();
    }

    @Test
    void yocoSelected_withBothKeys_boots() {
        assertThatCode(() -> validate("yoco", "sk_test_x", "whsec_x")).doesNotThrowAnyException();
    }

    @Test
    void yocoSelected_missingSecretKey_failsBoot() {
        assertThatThrownBy(() -> validate("yoco", "", "whsec_x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOCO_SECRET_KEY");
    }

    @Test
    void yocoSelected_missingWebhookSecret_failsBoot() {
        assertThatThrownBy(() -> validate("yoco", "sk_test_x", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOCO_WEBHOOK_SECRET");
    }

    @Test
    void yocoSelected_missingBoth_namesBoth() {
        assertThatThrownBy(() -> validate("yoco", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOCO_SECRET_KEY")
                .hasMessageContaining("YOCO_WEBHOOK_SECRET");
    }

    @Test
    void stripeSelected_withNoYocoConfigAtAll_bootsFine() {
        // The regression that would otherwise take production down: every
        // current deploy runs provider=stripe with no YOCO_* vars.
        assertThatCode(() -> validate("stripe", "", "")).doesNotThrowAnyException();
    }

    @Test
    void payfastSelected_withNoYocoConfigAtAll_bootsFine() {
        assertThatCode(() -> validate("payfast", "", "")).doesNotThrowAnyException();
    }

    @Test
    void providerMatchIsCaseAndWhitespaceInsensitive() {
        // PAYMENTS_PROVIDER comes from a Railway text box; " Yoco " is a
        // realistic value and must not silently skip validation.
        assertThatThrownBy(() -> validate(" Yoco ", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
