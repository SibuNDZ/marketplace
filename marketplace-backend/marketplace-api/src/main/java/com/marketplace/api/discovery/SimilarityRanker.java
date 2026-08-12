package com.marketplace.api.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks related-product candidates once relevance has already been measured.
 *
 * WHY THIS EXISTS SEPARATELY FROM ProductService: before this class, the
 * similar-items shelf was ordered by cosine similarity ALONE. Everything the
 * marketplace already knows about a product — its rating, its sales, how often
 * it is viewed — was computed hourly by PopularityJob and then ignored at rank
 * time. Two products that are equally relevant were ordered arbitrarily. This
 * puts the existing signals to work without letting them override relevance.
 *
 * THE ONE RULE: relevance dominates, quality only breaks near-ties.
 *
 *   score = relevance * (1 + qualityWeight * quality)
 *
 * Multiplicative, deliberately, and with a small weight. At the default 0.15 a
 * flawless product gets at most a 15% lift, so quality reorders neighbours that
 * were already close and can never drag an unrelated product onto the shelf.
 * The additive alternative is the trap: add a popularity term to a relevance
 * score and your best seller appears on every product page in the catalogue,
 * which is a bestseller list wearing a recommendation's clothes.
 *
 * TIERS, AND WHY SCORES ARE NEVER COMPARED ACROSS THEM: cosine similarity runs
 * 0.55-1.0 while ts_rank runs about 0.05-0.3. Those are different scales
 * measuring different things, and any formula that adds or interleaves them is
 * inventing a conversion nobody measured. So semantic matches are ranked as one
 * group, lexical-only matches as another, and the semantic group always comes
 * first. Within a group the comparison is honest because both sides came from
 * the same measurement. Mixed shelves happen in one real case: a vendor adds a
 * product and the embedding sweep has not reached it yet (up to 5 minutes).
 */
@Component
public class SimilarityRanker {

    /** Weights inside the quality term. They sum to 1.0 so quality stays 0-1
     *  and the qualityWeight ceiling above means what it says.
     *
     *  Rating leads because it is the only signal a shopper gave deliberately,
     *  and PopularityJob already Bayesian-shrinks it (prior m=5), so a lone
     *  five-star review does not outrank a product with twenty good ones.
     *  Sales are next: real money, but they lag a new listing badly. Views are
     *  last and nearly a tiebreaker, because a view is the cheapest signal
     *  there is and the one most distorted by the owner browsing their own
     *  storefront. */
    private static final double W_RATING = 0.6;
    private static final double W_SALES  = 0.3;
    private static final double W_VIEWS  = 0.1;

    private static final double MAX_RATING = 5.0;

    private final double qualityWeight;
    private final int vendorCap;

    public SimilarityRanker(@Value("${app.discovery.similar.quality-weight:0.15}") double qualityWeight,
                            @Value("${app.discovery.similar.vendor-cap:2}") int vendorCap) {
        this.qualityWeight = qualityWeight;
        this.vendorCap = vendorCap;
    }

    /**
     * One candidate, with everything ranking needs already resolved. Taking a
     * flat record rather than entities is what lets this be tested without a
     * database, which matters because the scoring is the part worth testing.
     *
     * Plain numbers rather than the ProductPopularity entity on purpose. That
     * entity is @Immutable with no public constructor, so depending on it here
     * would mean this class could only be tested against a live database —
     * and the scoring is the part most worth testing cheaply.
     *
     * @param relevance cosine when semantic, the SQL-side lexical score
     *                  otherwise. Only ever compared against candidates in the
     *                  same tier.
     * @param semantic  true when this came from embeddings; sets the tier.
     * @param lexicalMatch true when the product also shares real words with the
     *                  source. Purely for the reason string.
     * @param weightedRating 0-5, Bayesian-shrunk by PopularityJob. Zero when
     *                  the product has no popularity row yet, which is NORMAL
     *                  for anything created since the last hourly rebuild.
     */
    public record Candidate(Long productId,
                            Long vendorId,
                            double relevance,
                            boolean semantic,
                            boolean lexicalMatch,
                            double weightedRating,
                            long salesCount,
                            long views30d) {}

    /** A ranked result. The reason describes why the pair is RELATED, which is
     *  the shopper's question; it deliberately says nothing about quality,
     *  because "popular" is not a reason to think two products are alike. */
    public record Ranked(Long productId, double score, String reason) {}

    /**
     * Ranks candidates and applies the vendor cap. Input order is irrelevant;
     * ties break on product id so the same catalogue always produces the same
     * shelf (an unstable shelf looks broken to anyone refreshing a page).
     */
    public List<Ranked> rank(List<Candidate> candidates, int limit) {
        if (candidates.isEmpty() || limit <= 0) return List.of();

        // Normalise sales and views against the candidate set rather than the
        // whole catalogue. Self-calibrating: it behaves the same at 12 products
        // and at 12,000, and it needs no tuning constant that would go stale.
        long maxSales = candidates.stream().mapToLong(Candidate::salesCount).max().orElse(0);
        long maxViews = candidates.stream().mapToLong(Candidate::views30d).max().orElse(0);

        record Scored(Candidate candidate, double score) {}

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            double quality = quality(c, maxSales, maxViews);
            scored.add(new Scored(c, c.relevance() * (1 + qualityWeight * quality)));
        }

        // Semantic tier first, then score, then id. The tier comparison is the
        // one that must come first: see the class note on incomparable scales.
        scored.sort(Comparator
                .comparing((Scored s) -> s.candidate().semantic() ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
                .thenComparing(s -> s.candidate().productId()));

        // Vendor cap in two passes. One stall owning half the catalogue is the
        // normal shape of a young marketplace, and without a cap its products
        // fill every shelf on the site. The second pass matters as much as the
        // first: the cap must never SHRINK a shelf, only reorder it, so capped
        // items come back to fill slots that would otherwise sit empty.
        List<Ranked> primary = new ArrayList<>();
        List<Ranked> overflow = new ArrayList<>();
        Map<Long, Integer> perVendor = new HashMap<>();

        for (Scored s : scored) {
            Ranked ranked = new Ranked(s.candidate().productId(), s.score(), reason(s.candidate()));
            Long vendorId = s.candidate().vendorId();
            int taken = vendorId == null ? 0 : perVendor.getOrDefault(vendorId, 0);
            if (taken < vendorCap) {
                primary.add(ranked);
                if (vendorId != null) perVendor.put(vendorId, taken + 1);
            } else {
                overflow.add(ranked);
            }
        }

        if (primary.size() >= limit) return primary.subList(0, limit);
        List<Ranked> result = new ArrayList<>(primary);
        for (Ranked r : overflow) {
            if (result.size() >= limit) break;
            result.add(r);
        }
        return result;
    }

    /**
     * Quality in 0-1 from the read model. Sales and views go through log1p
     * before normalising: the difference between 0 and 5 sales says far more
     * about a product than the difference between 45 and 50, and a linear
     * scale lets one runaway seller flatten every other candidate to nearly
     * zero on that term.
     */
    private static double quality(Candidate c, long maxSales, long maxViews) {
        return W_RATING * clamp01(c.weightedRating() / MAX_RATING)
                + W_SALES * logNorm(c.salesCount(), maxSales)
                + W_VIEWS * logNorm(c.views30d(), maxViews);
    }

    private static double logNorm(long value, long max) {
        if (max <= 0 || value <= 0) return 0;
        return Math.log1p(value) / Math.log1p(max);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /**
     * Three values, each literally true of the candidate it describes, and
     * each a weaker claim than the one above it.
     *
     * The third exists because the lexical gate admits same-category rows that
     * share NO words with the source. Calling those "Shares keywords" would be
     * a plain lie about the one thing this string is for, and it is the sort of
     * lie nobody notices because it appears on the weakest results.
     *
     * This is also the only way to tell from outside the app whether a shelf
     * came from embeddings or fell back to text — a question that previously
     * needed a database query to answer.
     */
    private static String reason(Candidate c) {
        if (c.semantic()) return "Similar item";
        return c.lexicalMatch() ? "Shares keywords" : "Same category";
    }
}
