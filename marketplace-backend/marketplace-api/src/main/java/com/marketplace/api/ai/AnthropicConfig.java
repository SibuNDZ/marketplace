package com.marketplace.api.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * The Anthropic client used by the vendor listing drafter, and nothing else.
 *
 * FAULT ISOLATION — this is the R2 lesson applied to a second provider.
 *
 * JWT_SECRET and STRIPE_SECRET_KEY are fail-fast at boot: without them the
 * app is not safely runnable at all. This key is the opposite. Drafting is an
 * accelerant on one vendor form; checkout, browsing, and auth do not need it.
 * A missing or invalid ANTHROPIC_API_KEY must therefore degrade to "the draft
 * button 500s" and never to "the site is down".
 *
 * Two things are required for that, and the R2 incident proved that only one
 * of them is not enough:
 *
 *   1. @Lazy here, so the bean is not built during eager pre-instantiation.
 *   2. ObjectProvider<AnthropicClient> at the INJECTION SITE (see
 *      AnthropicListingDraftModel). @Lazy on a @Bean only skips eager
 *      creation — a normal singleton whose constructor demands a concrete
 *      AnthropicClient forces Spring to build it immediately anyway, which is
 *      exactly how a bad R2 env var crash-looped the whole API once.
 *
 * Do not "simplify" the injection site back to a direct AnthropicClient
 * parameter. AnthropicDraftFaultIsolationTest boots with a blank key and
 * fails at context-load time if that regresses.
 */
@Configuration
class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    @Bean
    @Lazy
    AnthropicClient anthropicClient(@Value("${app.ai.api-key:}") String apiKey) {
        // Trim defensively — a pasted key with a trailing newline authenticates
        // as garbage and returns a 401 that reads like a revoked key rather
        // than a whitespace problem. Same trap R2Config already documents.
        String key = apiKey == null ? "" : apiKey.trim();

        if (key.isBlank()) {
            // Thrown lazily, at first draft request, NOT at boot. The
            // ObjectProvider at the injection site is what makes that true.
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set — the listing drafter is unavailable. "
                            + "Every other feature is unaffected.");
        }

        // Never log the key or any prefix of it. Logging that it is configured
        // at all is the useful signal; the value never is.
        log.info("Anthropic client configured for the listing drafter");

        return AnthropicOkHttpClient.builder().apiKey(key).build();
    }
}
