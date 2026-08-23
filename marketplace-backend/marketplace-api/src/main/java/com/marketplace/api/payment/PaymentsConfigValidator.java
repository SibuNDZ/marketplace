package com.marketplace.api.payment;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail fast at boot when the SELECTED payment provider is missing keys or
 * has an unreadable mode — and stay silent for the providers that are not
 * live. A crash loop on the chosen provider is correct: the alternative is
 * an API that boots healthy and 502s every checkout.
 *
 * Replaces the Yoco-only {@code YocoConfigValidator}: Stripe used to fail
 * at property-bind time even when PayFast was selected (no default), and
 * PayFast could never fail (public sandbox defaults). Same shape for all
 * three now.
 */
@Component
public class PaymentsConfigValidator implements InitializingBean {

    private final String provider;
    private final String stripeSecret;
    private final String stripeWebhook;
    private final String yocoSecret;
    private final String yocoWebhook;
    private final String payfastMerchantId;
    private final String payfastMerchantKey;
    private final String payfastProcessUrl;
    private final String payfastValidateUrl;

    public PaymentsConfigValidator(
            @Value("${app.payments.provider:stripe}") String provider,
            @Value("${app.stripe.secret-key:}") String stripeSecret,
            @Value("${app.stripe.webhook-secret:}") String stripeWebhook,
            @Value("${app.yoco.secret-key:}") String yocoSecret,
            @Value("${app.yoco.webhook-secret:}") String yocoWebhook,
            @Value("${app.payfast.merchant-id:}") String payfastMerchantId,
            @Value("${app.payfast.merchant-key:}") String payfastMerchantKey,
            @Value("${app.payfast.process-url:}") String payfastProcessUrl,
            @Value("${app.payfast.validate-url:}") String payfastValidateUrl) {
        this.provider = provider;
        this.stripeSecret = stripeSecret;
        this.stripeWebhook = stripeWebhook;
        this.yocoSecret = yocoSecret;
        this.yocoWebhook = yocoWebhook;
        this.payfastMerchantId = payfastMerchantId;
        this.payfastMerchantKey = payfastMerchantKey;
        this.payfastProcessUrl = payfastProcessUrl;
        this.payfastValidateUrl = payfastValidateUrl;
    }

    @Override
    public void afterPropertiesSet() {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        switch (p) {
            case "stripe" -> validateStripe();
            case "yoco" -> validateYoco();
            case "payfast" -> validatePayfast();
            default -> throw new IllegalStateException(
                    "PAYMENTS_PROVIDER='" + provider + "' is not one of stripe, yoco, payfast.");
        }
    }

    private void validateStripe() {
        List<String> missing = new ArrayList<>();
        if (isBlank(stripeSecret)) missing.add("STRIPE_SECRET_KEY (app.stripe.secret-key)");
        if (isBlank(stripeWebhook)) missing.add("STRIPE_WEBHOOK_SECRET (app.stripe.webhook-secret)");
        failIfMissing("stripe", missing);
        String mode = PaymentHealth.modeFromKey(stripeSecret);
        if ("unknown".equals(mode)) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=stripe but STRIPE_SECRET_KEY does not start with "
                    + "sk_test_ or sk_live_, so the mode cannot be determined. "
                    + "Use a test key (sk_test_…) or a live key (sk_live_…).");
        }
        if (!isBlank(stripeWebhook) && !stripeWebhook.strip().startsWith("whsec_")) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=stripe but STRIPE_WEBHOOK_SECRET does not start with whsec_. "
                    + "Copy the signing secret from the Stripe webhook endpoint, not the secret key.");
        }
    }

    private void validateYoco() {
        List<String> missing = new ArrayList<>();
        if (isBlank(yocoSecret)) missing.add("YOCO_SECRET_KEY (app.yoco.secret-key)");
        if (isBlank(yocoWebhook)) missing.add("YOCO_WEBHOOK_SECRET (app.yoco.webhook-secret)");
        failIfMissing("yoco", missing);
        String mode = PaymentHealth.modeFromKey(yocoSecret);
        if ("unknown".equals(mode)) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=yoco but YOCO_SECRET_KEY does not start with "
                    + "sk_test_ or sk_live_, so the mode cannot be determined. "
                    + "Use Test keys or Live keys from the Yoco Checkout API tab.");
        }
        if (!isBlank(yocoWebhook) && !yocoWebhook.strip().startsWith("whsec_")) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=yoco but YOCO_WEBHOOK_SECRET does not start with whsec_. "
                    + "The secret is returned once when you register the webhook.");
        }
    }

    private void validatePayfast() {
        List<String> missing = new ArrayList<>();
        if (isBlank(payfastMerchantId)) missing.add("PAYFAST_MERCHANT_ID (app.payfast.merchant-id)");
        if (isBlank(payfastMerchantKey)) missing.add("PAYFAST_MERCHANT_KEY (app.payfast.merchant-key)");
        failIfMissing("payfast", missing);
        String processMode = PaymentHealth.modeFromPayfastUrl(payfastProcessUrl);
        String validateMode = PaymentHealth.modeFromPayfastUrl(payfastValidateUrl);
        if ("unknown".equals(processMode)) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=payfast but PAYFAST_PROCESS_URL is not a sandbox.payfast.co.za "
                    + "or www.payfast.co.za URL, so the mode cannot be determined.");
        }
        if (!processMode.equals(validateMode)) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=payfast but process URL is " + processMode
                    + " while validate URL is " + validateMode
                    + ". Sandbox and live endpoints must not be mixed.");
        }
    }

    private void failIfMissing(String name, List<String> missing) {
        if (missing.isEmpty()) return;
        throw new IllegalStateException(
                "PAYMENTS_PROVIDER=" + name + " but required configuration is missing: "
                + String.join(", ", missing)
                + ". Set it, or switch PAYMENTS_PROVIDER to the provider whose keys are present.");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
