package com.marketplace.api.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Process-local snapshot for {@code GET /api/v1/payments/health}.
 * Never holds or returns secret values — only provider name, inferred mode,
 * whether the selected provider has non-blank keys, and the last 502 class.
 */
@Component
public class PaymentHealth {

    public record Snapshot(String provider, String mode, boolean configured, String lastErrorType) {}

    private final String provider;
    private final String mode;
    private final boolean configured;
    private volatile String lastErrorType;

    public PaymentHealth(@Value("${app.payments.provider:stripe}") String provider,
                         @Value("${app.stripe.secret-key:}") String stripeSecret,
                         @Value("${app.yoco.secret-key:}") String yocoSecret,
                         @Value("${app.payfast.merchant-id:}") String payfastMerchantId,
                         @Value("${app.payfast.merchant-key:}") String payfastMerchantKey,
                         @Value("${app.payfast.process-url:}") String payfastProcessUrl) {
        this.provider = provider == null ? "stripe" : provider.trim().toLowerCase();
        this.mode = inferMode(this.provider, stripeSecret, yocoSecret, payfastProcessUrl);
        this.configured = isConfigured(this.provider, stripeSecret, yocoSecret,
                payfastMerchantId, payfastMerchantKey);
    }

    public Snapshot snapshot() {
        return new Snapshot(provider, mode, configured, lastErrorType);
    }

    public void recordErrorType(String type) {
        this.lastErrorType = type;
    }

    static String inferMode(String provider, String stripeSecret, String yocoSecret, String processUrl) {
        return switch (provider) {
            case "stripe" -> modeFromKey(stripeSecret);
            case "yoco" -> modeFromKey(yocoSecret);
            case "payfast" -> modeFromPayfastUrl(processUrl);
            default -> "unknown";
        };
    }

    static String modeFromKey(String secret) {
        if (secret == null || secret.isBlank()) return "unknown";
        String s = secret.strip();
        if (s.startsWith("sk_live_")) return "live";
        if (s.startsWith("sk_test_")) return "test";
        return "unknown";
    }

    static String modeFromPayfastUrl(String processUrl) {
        if (processUrl == null || processUrl.isBlank()) return "unknown";
        String u = processUrl.toLowerCase();
        if (u.contains("sandbox.payfast")) return "test";
        if (u.contains("payfast.co.za")) return "live";
        return "unknown";
    }

    private static boolean isConfigured(String provider, String stripeSecret, String yocoSecret,
                                        String payfastMerchantId, String payfastMerchantKey) {
        return switch (provider) {
            case "stripe" -> notBlank(stripeSecret);
            case "yoco" -> notBlank(yocoSecret);
            case "payfast" -> notBlank(payfastMerchantId) && notBlank(payfastMerchantKey);
            default -> false;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
