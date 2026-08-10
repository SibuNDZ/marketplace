package com.marketplace.api.payment;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail fast at boot when Yoco is the SELECTED provider and its keys are
 * missing — and stay completely silent otherwise.
 *
 * This conditional shape is new. The codebase had only the two extremes:
 * STRIPE_SECRET_KEY has no default and so fails the boot even when PayFast is
 * live, while every PayFast property carries a public-sandbox default and so
 * can never fail at all. Neither is right for a third provider. Yoco has no
 * public sandbox pair to default to (keys are per-merchant, from the Test keys
 * tab), so a default would be a lie; but hard-failing an unrelated Stripe
 * deploy because YOCO_SECRET_KEY is unset would be worse.
 *
 * So: blank defaults, and this check turns them into a boot failure only on
 * the deploy that actually selects yoco. A crash loop there is correct — the
 * alternative is an API that boots healthy and 502s every checkout.
 *
 * No client bean is introduced by the Yoco slice (YocoCheckoutService builds a
 * JDK HttpClient inline, as HttpPayfastValidator does), so the @Lazy +
 * ObjectProvider fault-isolation dance from AnthropicConfig/R2Config does not
 * apply here — there is no eagerly-constructed provider bean to isolate.
 */
@Component
public class YocoConfigValidator implements InitializingBean {

    private final String provider;
    private final String secretKey;
    private final String webhookSecret;

    public YocoConfigValidator(@Value("${app.payments.provider:stripe}") String provider,
                               @Value("${app.yoco.secret-key:}") String secretKey,
                               @Value("${app.yoco.webhook-secret:}") String webhookSecret) {
        this.provider = provider;
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public void afterPropertiesSet() {
        if (!"yoco".equalsIgnoreCase(provider.trim())) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (secretKey.isBlank()) {
            missing.add("YOCO_SECRET_KEY (app.yoco.secret-key)");
        }
        if (webhookSecret.isBlank()) {
            missing.add("YOCO_WEBHOOK_SECRET (app.yoco.webhook-secret)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "PAYMENTS_PROVIDER=yoco but required configuration is missing: "
                    + String.join(", ", missing)
                    + ". Set it, or switch PAYMENTS_PROVIDER back to stripe.");
        }
    }
}
