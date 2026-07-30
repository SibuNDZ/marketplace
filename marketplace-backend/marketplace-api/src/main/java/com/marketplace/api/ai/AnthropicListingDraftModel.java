package com.marketplace.api.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

/**
 * The real provider call. Everything model-vendor-specific lives here and
 * nowhere else, so swapping providers is one class.
 *
 * ObjectProvider<AnthropicClient>, not a direct AnthropicClient — see
 * AnthropicConfig for why. This is the half of the fault-isolation fix that
 * lives at the injection site, and it is the half the R2 incident proved you
 * cannot omit.
 */
@Component
class AnthropicListingDraftModel implements ListingDraftModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicListingDraftModel.class);

    private final ObjectProvider<AnthropicClient> clientProvider;
    private final String model;
    private final long maxTokens;

    AnthropicListingDraftModel(ObjectProvider<AnthropicClient> clientProvider,
                               @Value("${app.ai.model}") String model,
                               @Value("${app.ai.max-tokens:1024}") long maxTokens) {
        this.clientProvider = clientProvider;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String draft(byte[] imageBytes, String mediaType, String prompt) {
        long startedAt = System.currentTimeMillis();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                // Low effort deliberately. This is a short, scoped extraction
                // from a single image, and a vendor is staring at a spinner
                // while it runs. Raising effort buys deliberation this task
                // does not need and latency the form cannot afford.
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .build())
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .mediaType(toMediaType(mediaType))
                                        .data(Base64.getEncoder().encodeToString(imageBytes))
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(TextBlockParam.builder()
                                .text(prompt)
                                .build())))
                .build();

        // Resolved OUTSIDE the try on purpose, so the two failure classes stay
        // distinguishable:
        //
        //   here  — the bean cannot be built (missing/blank ANTHROPIC_API_KEY).
        //           That is OUR misconfiguration, so it propagates to the
        //           catch-all handler as a 500. Contained to this endpoint by
        //           the @Lazy + ObjectProvider pairing; the rest of the site
        //           never touches this bean.
        //   below — the provider itself failed (transport, auth, their rate
        //           limit). Not our bug, so 502 via DraftProviderException.
        //
        // Collapsing these into one catch would report a blank env var as
        // "the drafting provider is having problems", sending someone to
        // check Anthropic's status page over a Railway variable we forgot.
        AnthropicClient client = clientProvider.getObject();

        Message response;
        try {
            response = client.messages().create(params);
        } catch (RuntimeException e) {
            throw new DraftExceptions.DraftProviderException(
                    "Drafting provider call failed", e);
        }

        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .findFirst()
                .orElseThrow(() -> new DraftExceptions.DraftProviderException(
                        "Drafting provider returned no text block"));

        // The cost audit trail. Model, latency, and tokens per call — enough
        // to reconcile a bill and spot a prompt that has grown expensive.
        // The image bytes and the base64 payload are never logged.
        log.info("Listing draft generated: model={} latencyMs={} inputTokens={} outputTokens={}",
                model,
                System.currentTimeMillis() - startedAt,
                response.usage().inputTokens(),
                response.usage().outputTokens());

        return text;
    }

    private static Base64ImageSource.MediaType toMediaType(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png"  -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            // Unreachable: ImageValidation ran first and its whitelist is the
            // same three types. Loud rather than silent if that ever drifts.
            default -> throw new IllegalStateException(
                    "Content type passed validation but has no media type mapping: " + contentType);
        };
    }
}
