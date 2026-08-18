package com.marketplace.api.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider-flag contract, as a plain unit test — no 40-second context boot.
 *
 * Missing keys (or an unreadable mode) are a BOOT FAILURE when that provider
 * is selected, and a complete non-event otherwise.
 */
class PaymentsConfigValidatorTest {

    private static void validate(String provider,
                                 String stripeSecret, String stripeWebhook,
                                 String yocoSecret, String yocoWebhook,
                                 String pfId, String pfKey, String process, String validateUrl) {
        new PaymentsConfigValidator(provider, stripeSecret, stripeWebhook,
                yocoSecret, yocoWebhook, pfId, pfKey, process, validateUrl)
                .afterPropertiesSet();
    }

    private static void yoco(String provider, String secret, String webhook) {
        validate(provider, "", "", secret, webhook, "", "", "", "");
    }

    private static void stripe(String provider, String secret, String webhook) {
        validate(provider, secret, webhook, "", "", "", "", "", "");
    }

    private static void payfast(String provider, String id, String key, String process, String validateUrl) {
        validate(provider, "", "", "", "", id, key, process, validateUrl);
    }

    @Test
    void yocoSelected_withBothKeys_boots() {
        assertThatCode(() -> yoco("yoco", "sk_test_x", "whsec_x")).doesNotThrowAnyException();
    }

    @Test
    void yocoSelected_missingSecretKey_failsBoot() {
        assertThatThrownBy(() -> yoco("yoco", "", "whsec_x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOCO_SECRET_KEY");
    }

    @Test
    void yocoSelected_missingWebhookSecret_failsBoot() {
        assertThatThrownBy(() -> yoco("yoco", "sk_test_x", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOCO_WEBHOOK_SECRET");
    }

    @Test
    void yocoSelected_missingBoth_namesBoth() {
        assertThatThrownBy(() -> yoco("yoco", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOCO_SECRET_KEY")
                .hasMessageContaining("YOCO_WEBHOOK_SECRET");
    }

    @Test
    void yocoSelected_unreadableMode_failsBoot() {
        assertThatThrownBy(() -> yoco("yoco", "not-a-yoco-key", "whsec_x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sk_test_");
    }

    @Test
    void stripeSelected_withBothKeys_boots() {
        assertThatCode(() -> stripe("stripe", "sk_live_abc", "whsec_abc")).doesNotThrowAnyException();
    }

    @Test
    void stripeSelected_missingSecret_failsBoot() {
        assertThatThrownBy(() -> stripe("stripe", "", "whsec_x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
    }

    @Test
    void stripeSelected_unreadableMode_failsBoot() {
        assertThatThrownBy(() -> stripe("stripe", "rk_test_wrong_prefix", "whsec_x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sk_live_");
    }

    @Test
    void payfastSelected_sandboxPair_boots() {
        assertThatCode(() -> payfast("payfast", "10000100", "key",
                "https://sandbox.payfast.co.za/eng/process",
                "https://sandbox.payfast.co.za/eng/query/validate"))
                .doesNotThrowAnyException();
    }

    @Test
    void payfastSelected_mixedMode_failsBoot() {
        assertThatThrownBy(() -> payfast("payfast", "10000100", "key",
                "https://sandbox.payfast.co.za/eng/process",
                "https://www.payfast.co.za/eng/query/validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be mixed");
    }

    @Test
    void payfastSelected_missingMerchantId_failsBoot() {
        assertThatThrownBy(() -> payfast("payfast", "", "key",
                "https://sandbox.payfast.co.za/eng/process",
                "https://sandbox.payfast.co.za/eng/query/validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYFAST_MERCHANT_ID");
    }

    @Test
    void stripeSelected_withNoYocoConfigAtAll_bootsFine() {
        assertThatCode(() -> stripe("stripe", "sk_test_x", "whsec_x")).doesNotThrowAnyException();
    }

    @Test
    void payfastSelected_withNoYocoConfigAtAll_bootsFine() {
        assertThatCode(() -> payfast("payfast", "id", "key",
                "https://www.payfast.co.za/eng/process",
                "https://www.payfast.co.za/eng/query/validate"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownProvider_failsBoot() {
        assertThatThrownBy(() -> yoco("paypal", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paypal");
    }

    @Test
    void providerMatchIsCaseAndWhitespaceInsensitive() {
        assertThatThrownBy(() -> yoco(" Yoco ", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
