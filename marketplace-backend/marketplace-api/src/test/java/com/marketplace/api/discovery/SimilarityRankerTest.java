package com.marketplace.api.discovery;

import com.marketplace.api.discovery.SimilarityRanker.Candidate;
import com.marketplace.api.discovery.SimilarityRanker.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests: no Spring context, no database. That is the whole reason
 * Candidate takes plain numbers instead of the ProductPopularity entity.
 *
 * These tests exist to pin down the RULES, not the arithmetic. The one that
 * matters most is relevanceBeatsQuality: the blend is only safe as long as
 * popularity cannot drag an unrelated product onto a shelf, and a future
 * "let's just bump the weight" change should fail here rather than in
 * production.
 */
class SimilarityRankerTest {

    /** Defaults from application.yml, restated so a config change does not
     *  silently rewrite what these tests assert. */
    private final SimilarityRanker ranker = new SimilarityRanker(0.15, 2);

    private static Candidate semantic(long id, long vendorId, double cosine) {
        return new Candidate(id, vendorId, cosine, true, false, 0, 0, 0);
    }

    private static Candidate semantic(long id, long vendorId, double cosine,
                                      double rating, long sales, long views) {
        return new Candidate(id, vendorId, cosine, true, false, rating, sales, views);
    }

    private static Candidate lexical(long id, long vendorId, double rank) {
        return new Candidate(id, vendorId, rank, false, true, 0, 0, 0);
    }

    private static List<Long> ids(List<Ranked> ranked) {
        return ranked.stream().map(Ranked::productId).toList();
    }

    @Test
    @DisplayName("quality reorders products that are equally relevant")
    void qualityBreaksTies() {
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.70),                        // no signals at all
                semantic(2L, 20L, 0.70, 4.5, 12, 300)           // same relevance, real trade
        ), 10);

        assertThat(ids(ranked)).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("relevance dominates: a flawless product cannot outrank a clearly better match")
    void relevanceBeatsQuality() {
        // 0.90 with nothing vs 0.70 with everything. At qualityWeight 0.15 the
        // best possible lift is 15%, so 0.70 tops out at 0.805 and loses.
        // This is the guard rail: raise qualityWeight past ~0.29 and this test
        // fails, which is the intended alarm.
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.90),
                semantic(2L, 20L, 0.70, 5.0, 999, 99999)
        ), 10);

        assertThat(ids(ranked)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("the blend is multiplicative, not additive")
    void qualityLiftScalesWithRelevance() {
        // These numbers are chosen to separate the two formulas, because the
        // test above passes under either one:
        //   multiplicative  0.56 * 1.15  = 0.644  -> loses to 0.68, correct
        //   additive        0.56 + 0.15  = 0.710  -> beats 0.68, wrong
        //
        // Additive is the trap the discovery engine's mixer fell into: a flat
        // popularity bonus is worth the same to a barely-related product as to
        // a strong match, so weak matches with good numbers climb onto shelves
        // they do not belong on. Multiplying keeps the bonus proportional to
        // how related the product actually is.
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.68),
                semantic(2L, 20L, 0.56, 5.0, 999, 99999)
        ), 10);

        assertThat(ids(ranked)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("semantic outranks lexical even when the lexical number is larger")
    void semanticTierWinsRegardlessOfScale() {
        // ts_rank * 10 routinely exceeds 1.0 while cosine never can, so
        // comparing the two numerically would put text matches on top of every
        // shelf. The tier split is what stops that.
        List<Ranked> ranked = ranker.rank(List.of(
                lexical(1L, 10L, 3.2),
                semantic(2L, 20L, 0.56)
        ), 10);

        assertThat(ids(ranked)).containsExactly(2L, 1L);
        assertThat(ranked.get(0).reason()).isEqualTo("Similar item");
        assertThat(ranked.get(1).reason()).isEqualTo("Shares keywords");
    }

    @Test
    @DisplayName("a same-category match does not claim to share keywords")
    void categoryOnlyMatchIsLabelledHonestly() {
        // The lexical gate admits same-category rows that share no words at
        // all. Labelling those "Shares keywords" would be false on exactly the
        // results nobody scrutinises.
        Candidate categoryOnly = new Candidate(1L, 10L, 0.2, false, false, 0, 0, 0);

        assertThat(ranker.rank(List.of(categoryOnly), 10).get(0).reason())
                .isEqualTo("Same category");
    }

    @Test
    @DisplayName("one vendor cannot fill a shelf")
    void vendorCapApplies() {
        // Vendor 10 owns the four most relevant products. Cap is 2.
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.95),
                semantic(2L, 10L, 0.94),
                semantic(3L, 10L, 0.93),
                semantic(4L, 10L, 0.92),
                semantic(5L, 20L, 0.60),
                semantic(6L, 30L, 0.59)
        ), 4);

        assertThat(ids(ranked)).containsExactly(1L, 2L, 5L, 6L);
    }

    @Test
    @DisplayName("the cap reorders a shelf but never shrinks it")
    void vendorCapRefillsRatherThanTruncating() {
        // Every candidate belongs to one vendor. A cap that filtered would
        // return 2; it must return 4, just with the capped items last.
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.95),
                semantic(2L, 10L, 0.94),
                semantic(3L, 10L, 0.93),
                semantic(4L, 10L, 0.92)
        ), 4);

        assertThat(ids(ranked)).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("a product with no popularity row still competes on relevance")
    void missingPopularityIsNotExclusion() {
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.80),                       // brand new, no row
                semantic(2L, 20L, 0.60, 5.0, 50, 5000)         // established
        ), 10);

        assertThat(ids(ranked)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("sales are log-scaled so one runaway seller cannot flatten the field")
    void salesUseLogScale() {
        // 5 sales vs 500 vs 0, all equally relevant. Log scaling means the
        // 5-sale product keeps a meaningful share of the sales term rather
        // than rounding to nothing beside the 500.
        List<Ranked> ranked = ranker.rank(List.of(
                semantic(1L, 10L, 0.70, 0, 0, 0),
                semantic(2L, 20L, 0.70, 0, 5, 0),
                semantic(3L, 30L, 0.70, 0, 500, 0)
        ), 10);

        assertThat(ids(ranked)).containsExactly(3L, 2L, 1L);

        // The claim, stated precisely: how much of the leader's sales lift
        // does a modest seller keep? Relevance is identical at 0.70, so
        // dividing it out leaves exactly the quality lift each product earned.
        double topLift = ranked.get(0).score() / 0.70 - 1;
        double midLift = ranked.get(1).score() / 0.70 - 1;

        // log1p(5)/log1p(500) = 0.288, so 5 sales is worth ~29% of what 500
        // sales is worth. Under linear scaling it would be worth 1%, and a
        // single runaway seller would flatten every other product's sales
        // term to nothing. That difference is the entire reason for log1p.
        assertThat(midLift / topLift).isBetween(0.25, 0.35);
    }

    @Test
    @DisplayName("ordering is stable for identical candidates")
    void tiesBreakDeterministically() {
        List<Ranked> first = ranker.rank(List.of(
                semantic(7L, 10L, 0.70), semantic(3L, 20L, 0.70), semantic(5L, 30L, 0.70)), 10);
        List<Ranked> second = ranker.rank(List.of(
                semantic(5L, 30L, 0.70), semantic(7L, 10L, 0.70), semantic(3L, 20L, 0.70)), 10);

        assertThat(ids(first)).containsExactly(3L, 5L, 7L);
        assertThat(ids(second)).isEqualTo(ids(first));
    }

    @Test
    @DisplayName("empty in, empty out")
    void emptyInput() {
        assertThat(ranker.rank(List.of(), 10)).isEmpty();
        assertThat(ranker.rank(List.of(semantic(1L, 10L, 0.9)), 0)).isEmpty();
    }
}
