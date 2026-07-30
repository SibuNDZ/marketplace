package com.marketplace.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.ai.ListingDraftDtos.ListingDraft;
import com.marketplace.api.dto.CategoryDtos.CategoryOption;
import com.marketplace.api.service.CategoryService;
import com.marketplace.api.storage.ImageValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a vendor photo into a DRAFT listing. Persists nothing, ever.
 *
 * The whole design assumption is that the model output is untrusted text from
 * a third party that happens to be good at this. Everything it returns is
 * re-validated here: the category against the live table, the lengths against
 * the real column limits, the JSON against a schema. Nothing reaches the
 * vendor's form that this class did not check.
 */
@Service
public class ListingDraftService {

    private static final Logger log = LoggerFactory.getLogger(ListingDraftService.class);

    /** Matches the Product.name column cap (@Size(max = 255)). */
    private static final int MAX_NAME = 255;
    /** Matches the ProductRequest.description constraint (@Size(max = 2000)). */
    private static final int MAX_DESCRIPTION = 2000;
    /** Fallback when the model invents a slug. Always exists — seeded in V14. */
    private static final String FALLBACK_SLUG = "other";

    private static final String DISCLAIMER =
            "AI-drafted from your photo. Review every field before publishing.";

    private final ListingDraftModel model;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    public ListingDraftService(ListingDraftModel model,
                               CategoryService categoryService,
                               ObjectMapper objectMapper) {
        this.model = model;
        this.categoryService = categoryService;
        this.objectMapper = objectMapper;
    }

    public ListingDraft draft(MultipartFile file) {
        // Same validator the upload path uses — one rule set, so a photo that
        // drafts is by definition a photo that will upload.
        ImageValidation.validateAndGetExtension(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<CategoryOption> options = categoryService.options();
        String raw = model.draft(bytes, file.getContentType(), buildPrompt(options));

        return parseAndValidate(raw, options);
    }

    /**
     * The anti-hallucination contract.
     *
     * This is the same rule the rest of the platform already follows — never
     * assert what cannot be verified — applied to a surface that is unusually
     * good at producing confident, plausible, false specifics. A model looking
     * at a jar of honey will happily write "raw organic wildflower honey,
     * 500g, ethically sourced" from a photo that establishes none of those
     * four things. On a marketplace that is not a quality problem, it is a
     * consumer-protection one: "organic" and "halal" are regulated claims, and
     * a weight the vendor never entered is a live dispute with a buyer.
     *
     * So the prohibitions are enumerated rather than left to a general
     * "be accurate" instruction, which models reliably interpret as licence to
     * describe what is probably true.
     */
    private String buildPrompt(List<CategoryOption> options) {
        String slugList = options.stream()
                .map(o -> o.parentSlug() == null
                        ? "  " + o.slug() + "  (" + o.name() + ")"
                        : "  " + o.slug() + "  (" + o.parentSlug() + " / " + o.name() + ")")
                .collect(Collectors.joining("\n"));

        return """
               You are drafting a product listing for a South African local marketplace \
               from a single vendor-supplied photo. The vendor will review and edit \
               everything you write before it is published.

               Return ONLY a JSON object, with no prose before or after it and no \
               markdown code fences. Exactly this shape:

               {"name": "...", "description": "...", "categorySlug": "..."}

               NEVER assert any of the following unless it is printed on a label that \
               is clearly legible in the photo, in which case you may quote it:
                 - certifications: organic, halal, kosher, free-range, fair-trade
                 - health, nutritional, dietary, or medicinal claims
                 - provenance, farm, region, or country of origin
                 - weights, volumes, quantities, or dimensions
                 - materials or ingredients

               If you cannot see it, do not write it. A shorter, plainer description \
               is always the correct choice over a richer one you cannot support.

               NEVER mention price, discounts, shipping, or availability.

               Tone: plain and warm, sentence case, the register of "Raw wildflower \
               honey, hand-harvested" rather than marketing copy. No emoji, no \
               exclamation marks, and never the words premium, luxury, or best.

               name: a short product name, at most 80 characters.
               description: 1 to 3 sentences, at most 300 characters.
               categorySlug: EXACTLY one slug from the list below. Choose the most \
               specific one that fits. If nothing fits, use "other".

               Category slugs:
               %s
               """.formatted(slugList);
    }

    private ListingDraft parseAndValidate(String raw, List<CategoryOption> options) {
        String json = stripFences(raw);

        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (IOException e) {
            throw unusable(raw, "not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw unusable(raw, "not a JSON object");
        }

        String name = text(node, "name");
        String description = text(node, "description");
        String categorySlug = text(node, "categorySlug");

        if (name.isBlank()) {
            throw unusable(raw, "missing name");
        }

        // The model saw the slug list and can still invent one. A 404 here
        // would punish the vendor for the model's mistake, so an unrecognised
        // slug degrades to the catch-all and the vendor fixes it in the form —
        // which they have to review anyway.
        Set<String> known = options.stream()
                .map(CategoryOption::slug)
                .collect(Collectors.toSet());
        if (!known.contains(categorySlug)) {
            log.warn("Draft model returned unknown category slug '{}' — falling back to '{}'",
                    categorySlug, FALLBACK_SLUG);
            categorySlug = FALLBACK_SLUG;
        }

        // Clamp server-side against the real column limits. The prompt asks
        // for 80 and 300 characters; the prompt is a request, not a guarantee,
        // and a 3000-character description would fail validation on save with
        // an error the vendor cannot act on.
        return new ListingDraft(
                clamp(name, MAX_NAME),
                clamp(description, MAX_DESCRIPTION),
                categorySlug,
                DISCLAIMER);
    }

    /**
     * Models add markdown fences even when told not to. The prompt forbids
     * them; this strips them anyway, because a fenced-but-otherwise-perfect
     * response is not worth failing a vendor's request over.
     */
    static String stripFences(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        // Drop the opening fence line (```json / ```) and any closing fence.
        s = s.substring(firstNewline + 1);
        int closing = s.lastIndexOf("```");
        return (closing >= 0 ? s.substring(0, closing) : s).trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private static String clamp(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private DraftExceptions.DraftProviderException unusable(String raw, String why) {
        // Truncated: the raw response is untrusted third-party text and could
        // be arbitrarily long. Enough to diagnose, not enough to flood the log.
        String truncated = raw == null ? "null"
                : raw.substring(0, Math.min(raw.length(), 500));
        log.warn("Draft model returned unusable output ({}): {}", why, truncated);
        return new DraftExceptions.DraftProviderException("Unusable model output: " + why);
    }
}
