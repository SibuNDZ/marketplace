package com.marketplace.api.discovery;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.Review;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ReviewRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.OrderAdminService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.ProductService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the discovery slice.
 *
 * Async determinism: the inner @TestConfiguration overrides
 * applicationTaskExecutor with SyncTaskExecutor, making @Async run
 * inline on the test thread. Without this, assertions on view counts
 * race the executor thread and the suite goes flaky.
 *
 * MOCK servlet (not RANDOM_PORT): service beans are invoked directly for
 * most tests; MockMvc handles the two HTTP assertions (auth check).
 * RANDOM_PORT can introduce JPA transaction-scope differences that make
 * direct EntityManager use in @Scheduled jobs behave unexpectedly.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DiscoveryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    /** Makes @Async run synchronously so assertions don't race the executor. */
    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean(name = "applicationTaskExecutor")
        @Primary
        public TaskExecutor taskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired OrderService          orderService;
    @Autowired OrderAdminService     orderAdminService;
    @Autowired ProductService        productService;
    @Autowired ProductViewRecorder   viewRecorder;
    @Autowired ProductViewRepository viewRepository;
    @Autowired FavoriteService       favoriteService;
    @Autowired FavoriteRepository    favoriteRepository;
    @Autowired PopularityJob         popularityJob;
    @Autowired ProductRepository     productRepository;
    @Autowired ReviewRepository      reviewRepository;
    @Autowired UserRepository        userRepository;
    @Autowired JdbcTemplate          jdbc;
    @Autowired TestFixtures          fixtures;
    @Autowired MockMvc               mockMvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager tm;

    // ── helpers ─────────────────────────────────────────────────────────

    private long popularitySales(Long productId) {
        Long val = jdbc.queryForObject(
                "SELECT sales_count FROM product_popularity WHERE product_id = ?",
                Long.class, productId);
        return val != null ? val : -1L;
    }

    private double popularityRating(Long productId) {
        Double val = jdbc.queryForObject(
                "SELECT weighted_rating FROM product_popularity WHERE product_id = ?",
                Double.class, productId);
        return val != null ? val : -1.0;
    }

    /**
     * Creates a fresh user per call (unique UUID suffix prevents email clashes
     * across reviews and across test methods sharing the same DB).
     */
    private void addReview(Long productId, int rating) {
        String name = "rev-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = fixtures.customer(name);
        Review r = new Review();
        r.setProduct(productRepository.getReferenceById(productId));
        r.setUser(userRepository.getReferenceById(u.getId()));
        r.setRating(rating);
        r.setComment("test");
        reviewRepository.save(r);
    }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    void popularity_ranksBySales_excludingNonKeptStates() {
        Product a = fixtures.product("Pop A", "SKU-PA-1", new BigDecimal("10"), 10);
        Product b = fixtures.product("Pop B", "SKU-PB-1", new BigDecimal("10"), 10);
        Product c = fixtures.product("Pop C", "SKU-PC-1", new BigDecimal("10"), 10);
        User admin = fixtures.admin("pop-admin1");

        // A: 3 units DELIVERED (kept sale — counts)
        User buyerA = fixtures.customerWithCart("pop-ba1", a, 3);
        var orderA = orderService.placeOrder(buyerA.getId());
        fixtures.deliverOrder(orderA.id(), admin.getId());

        // B: 5 units stay PENDING (no payment — excluded)
        User buyerB = fixtures.customerWithCart("pop-bb1", b, 5);
        orderService.placeOrder(buyerB.getId());

        // C: 4 units REFUNDED (un-sold — excluded)
        User buyerC = fixtures.customerWithCart("pop-bc1", c, 4);
        var orderC = orderService.placeOrder(buyerC.getId());
        fixtures.deliverOrder(orderC.id(), admin.getId());
        orderAdminService.transition(orderC.id(), OrderStatus.REFUNDED, admin.getId(), "refund");

        popularityJob.rebuild();

        assertThat(popularitySales(a.getId())).isEqualTo(3);
        assertThat(popularitySales(b.getId())).isEqualTo(0);
        assertThat(popularitySales(c.getId())).isEqualTo(0);
    }

    @Test
    void bayesian_oneFiveStarLosesToManyGoodReviews() {
        // Pull the global mean well below 4.5 with background reviews at 2 stars.
        // V4 unique(user, product): each call to addReview creates a fresh user.
        Product noise1 = fixtures.product("Noise1", "SKU-N1-1", new BigDecimal("5"), 1);
        Product noise2 = fixtures.product("Noise2", "SKU-N2-1", new BigDecimal("5"), 1);
        for (int i = 0; i < 3; i++) addReview(noise1.getId(), 2);
        for (int i = 0; i < 3; i++) addReview(noise2.getId(), 2);
        // 6 reviews at 2.0 → C pulled toward ~2.x once X and Y are added

        Product x = fixtures.product("Product X", "SKU-BAY-X1", new BigDecimal("10"), 5);
        Product y = fixtures.product("Product Y", "SKU-BAY-Y1", new BigDecimal("10"), 5);

        addReview(x.getId(), 5);                            // X: one 5.0

        for (int i = 0; i < 5; i++) addReview(y.getId(), 5); // Y: 5× 5-stars
        for (int i = 0; i < 5; i++) addReview(y.getId(), 4); // Y: 5× 4-stars → avg 4.5, 10 reviews

        popularityJob.rebuild();

        double wrX = popularityRating(x.getId());
        double wrY = popularityRating(y.getId());
        assertThat(wrY).as("many-review 4.5 must rank above single 5.0 with low global mean")
                .isGreaterThan(wrX);
    }

    @Test
    void views_recordedOn200_notOn404() {
        Product p = fixtures.product("View Widget", "SKU-VW-1", new BigDecimal("10"), 5);
        User u = fixtures.customer("view-user1");
        long before = viewRepository.count();

        // 200 path — view recorded
        productService.get(p.getId(), u.getId());
        assertThat(viewRepository.count()).isEqualTo(before + 1);

        // Soft-delete then request — 404, no extra view recorded
        User vendor = userRepository.findByEmail("test-vendor@test.local").orElseThrow();
        productService.delete(p.getId(), UserPrincipal.from(vendor));
        assertThatThrownBy(() -> productService.get(p.getId(), u.getId()));
        assertThat(viewRepository.count()).isEqualTo(before + 1);  // unchanged
    }

    @Test
    void recentlyViewed_dedupes_andOrdersByLatest() {
        Product a = fixtures.product("RV A", "SKU-RV-A1", new BigDecimal("10"), 5);
        Product b = fixtures.product("RV B", "SKU-RV-B1", new BigDecimal("10"), 5);
        User u = fixtures.customer("rv-user1");
        Long uid = u.getId();

        viewRecorder.record(a.getId(), uid);
        viewRecorder.record(b.getId(), uid);
        viewRecorder.record(a.getId(), uid);  // A viewed latest

        var recents = viewRepository.recentProductIds(uid, PageRequest.of(0, 10));

        assertThat(recents).hasSize(2);
        assertThat(recents.get(0)).isEqualTo(a.getId());
        assertThat(recents.get(1)).isEqualTo(b.getId());
    }

    @Test
    void favorites_idempotentBothWays() {
        Product p = fixtures.product("Fav Widget", "SKU-FAV-1", new BigDecimal("10"), 5);
        User u = fixtures.customer("fav-user1");

        favoriteService.add(u.getId(), p.getId());
        favoriteService.add(u.getId(), p.getId());
        assertThat(favoriteRepository
                .findByUserIdAndProductIdIn(u.getId(), List.of(p.getId()))).hasSize(1);

        favoriteService.remove(u.getId(), p.getId());
        favoriteService.remove(u.getId(), p.getId());
        assertThat(favoriteRepository.existsByUserIdAndProductId(u.getId(), p.getId())).isFalse();
    }

    @Test
    void favorites_hideSoftDeleted() {
        Product p = fixtures.product("Ghost Widget", "SKU-GHOST-1", new BigDecimal("10"), 5);
        User u = fixtures.customer("ghost-user1");

        favoriteService.add(u.getId(), p.getId());

        User vendor = userRepository.findByEmail("test-vendor@test.local").orElseThrow();
        productService.delete(p.getId(), UserPrincipal.from(vendor));

        var page = favoriteService.list(u.getId(), PageRequest.of(0, 20));
        assertThat(page.getContent())
                .noneMatch(f -> f.getProduct().getId().equals(p.getId()));
    }

    @Test
    void retentionSweep_deletesOnlyOldRows() {
        Product p = fixtures.product("Sweep Widget", "SKU-SWEEP-1", new BigDecimal("10"), 5);
        User u = fixtures.customer("sweep-user1");

        // Insert two views and capture their IDs
        viewRecorder.record(p.getId(), u.getId());
        viewRecorder.record(p.getId(), u.getId());

        List<Long> viewIds = viewRepository.findAll().stream()
                .filter(v -> v.getProduct().getId().equals(p.getId()))
                .sorted(java.util.Comparator.comparing(v -> v.getId()))
                .map(v -> v.getId()).toList();
        assertThat(viewIds).hasSize(2);
        long oldId   = viewIds.get(0);
        long freshId = viewIds.get(1);

        // Backdate using Java LocalDateTime so it matches the sweep's cutoff math
        var backdated = java.time.LocalDateTime.now().minusDays(91);
        // JdbcTemplate.update() runs outside any Spring transaction context;
        // with auto-commit=false (required for cart pessimistic-lock correctness)
        // it must be wrapped in an explicit transaction or the UPDATE is not
        // committed before sweepOldViews() runs.
        new org.springframework.transaction.support.TransactionTemplate(tm)
                .execute(status -> {
                    jdbc.update("UPDATE product_views SET viewed_at = ? WHERE id = ?", backdated, oldId);
                    return null;
                });

        popularityJob.sweepOldViews();

        assertThat(viewRepository.existsById(oldId)).isFalse();    // swept
        assertThat(viewRepository.existsById(freshId)).isTrue();   // kept
    }

    @Test
    void me_routes_require_auth() throws Exception {
        mockMvc.perform(get("/api/v1/me/recently-viewed"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/favorites"))
                .andExpect(status().isUnauthorized());
    }

    // ── related products ─────────────────────────────────────────────────
    //
    // These run WITHOUT a Voyage key, so no product has an embedding and every
    // result arrives through the lexical tier. That is not a limitation of the
    // tests, it is the fallback path in production whenever the sweep has not
    // reached a product yet, and it is the path that exercises the native
    // scored query — the part unit tests cannot reach.
    //
    // Nonsense words are deliberate: every fixture product files under the
    // 'other' category, so the category branch of the relevance gate matches
    // ALL of them and a common word would drag in rows from other test methods
    // sharing this container.

    /**
     * Gives a product a hand-made unit vector, standing in for a Voyage call.
     * Only products with a non-null embedding are semantic candidates, and no
     * other fixture sets one, so this isolates the semantic tier to exactly
     * the products a test opts in.
     *
     * The array is inlined rather than bound: JdbcTemplate has no mapping for
     * double[] -> float8[], and these values are test literals. The explicit
     * transaction is required because hikari.auto-commit is false — the same
     * trap that made embedding writes vanish in production once already.
     */
    private void setEmbedding(Long productId, double... vector) {
        StringBuilder literal = new StringBuilder("ARRAY[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) literal.append(',');
            literal.append(vector[i]);
        }
        literal.append("]::double precision[]");
        new org.springframework.transaction.support.TransactionTemplate(tm).execute(status -> {
            jdbc.update("UPDATE products SET embedding = " + literal
                    + ", embedding_hash = 'test-hash', embedded_at = now() WHERE id = ?", productId);
            return null;
        });
    }

    /** Only the products this test made, in the order the shelf returned them. */
    private List<Long> shelfOrderAmong(Long sourceId, int limit, List<Long> mine) {
        return productService.similar(sourceId, limit).stream()
                .map(com.marketplace.api.dto.ProductDtos.ProductResponse::id)
                .filter(mine::contains)
                .toList();
    }

    @Test
    void similar_findsProductsSharingWords() {
        Product source = fixtures.product("Zibbertron Lantern", "SKU-ZB-SRC", new BigDecimal("50"), 5);
        Product match  = fixtures.product("Zibbertron Holder", "SKU-ZB-M1", new BigDecimal("40"), 5);

        var results = productService.similar(source.getId(), 6);

        assertThat(results).extracting(com.marketplace.api.dto.ProductDtos.ProductResponse::id)
                .contains(match.getId())
                .doesNotContain(source.getId());   // never recommend itself
    }

    @Test
    void similar_qualityBreaksTiesBetweenIdenticalTextMatches() {
        Product source = fixtures.product("Quaxil Teapot", "SKU-QX-SRC", new BigDecimal("50"), 5);
        // Identical names, so identical ts_rank and identical category bonus.
        // Text relevance cannot separate these two; only quality can.
        Product plain   = fixtures.product("Quaxil Mug", "SKU-QX-P1", new BigDecimal("40"), 5);
        Product praised = fixtures.product("Quaxil Mug", "SKU-QX-R1", new BigDecimal("40"), 5);

        addReview(praised.getId(), 5);
        addReview(praised.getId(), 5);
        addReview(praised.getId(), 4);
        popularityJob.rebuild();

        assertThat(shelfOrderAmong(source.getId(), 12, List.of(plain.getId(), praised.getId())))
                .containsExactly(praised.getId(), plain.getId());
    }

    @Test
    void similar_vendorCapStopsOneStallFillingTheShelf() {
        User hog   = fixtures.vendor("wibble_hog");
        User small = fixtures.vendor("wibble_small");

        Product source = fixtures.productForVendor(
                "Wibblesnap Kitchen", "SKU-WB-SRC", new BigDecimal("50"), 5, hog);
        // The hog's four products all match as strongly as each other.
        List<Long> hogged = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            hogged.add(fixtures.productForVendor(
                    "Wibblesnap Bowl", "SKU-WB-H" + i, new BigDecimal("40"), 5, hog).getId());
        }
        // One competitor, matching on the same word.
        Product rival = fixtures.productForVendor(
                "Wibblesnap Bowl", "SKU-WB-S1", new BigDecimal("40"), 5, small);

        List<Long> mine = new java.util.ArrayList<>(hogged);
        mine.add(rival.getId());
        List<Long> shelf = shelfOrderAmong(source.getId(), 6, mine);

        // Without the cap the rival would sit at index 4, behind all four of
        // the hog's listings. The cap pulls it up to third at the latest.
        assertThat(shelf.indexOf(rival.getId())).isLessThanOrEqualTo(2);
        // ...and the cap must not have dropped anyone: all five still present.
        assertThat(shelf).hasSize(5);
    }

    @Test
    void similar_categoryOnlyMatchIsNotCalledAKeywordMatch() {
        // A name no other product shares a word with. Everything that comes
        // back reached the shelf through the category branch alone, which also
        // exercises the NULL-safe keywordMatch projection.
        Product source = fixtures.product("Vorplequink Artifact", "SKU-VQ-SRC", new BigDecimal("50"), 5);
        fixtures.product("Grumbold Pediment", "SKU-VQ-O1", new BigDecimal("40"), 5);

        var results = productService.similar(source.getId(), 6);

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(
                        com.marketplace.api.dto.ProductDtos.ProductResponse::similarityReason)
                .containsOnly("Same category");
    }

    @Test
    void similar_semanticShelfIsNotPaddedWithSameCategoryProducts() {
        // Production has an embedding on every product, so this is the normal
        // path, and the danger is subtle: once lexical results are allowed to
        // FILL a short shelf, the same-category rows the lexical gate admits
        // would quietly appear behind every genuine match on the site.
        Product source = fixtures.product("Ploofnix Vessel", "SKU-PX-SRC", new BigDecimal("50"), 5);
        Product related = fixtures.product("Snorkbeam Chalice", "SKU-PX-R1", new BigDecimal("40"), 5);
        // The decoy is what makes this test mean anything. It shares the
        // 'other' category with the source, shares no words, and has no
        // embedding, so it is exactly the padding this test forbids. Relying
        // on other test methods to supply such a row would make the assertion
        // silently vacuous whenever this test ran alone — which it did.
        Product decoy = fixtures.product("Zarquon Trivet", "SKU-PX-D1", new BigDecimal("30"), 5);

        setEmbedding(source.getId(), 1.0, 0.0, 0.0);
        setEmbedding(related.getId(), 0.9, 0.4359, 0.0);   // cosine 0.9, comfortably related

        var results = productService.similar(source.getId(), 6);

        assertThat(results).extracting(com.marketplace.api.dto.ProductDtos.ProductResponse::id)
                .doesNotContain(decoy.getId());
        assertThat(results).extracting(
                        com.marketplace.api.dto.ProductDtos.ProductResponse::similarityReason)
                .containsOnly("Similar item");
        assertThat(results).extracting(com.marketplace.api.dto.ProductDtos.ProductResponse::id)
                .contains(related.getId());
    }

    @Test
    void similar_semanticOutranksAStrongerLookingTextMatch() {
        // ts_rank * 10 comfortably exceeds 1.0 while cosine cannot, so ranking
        // the two on one scale would put text matches above every semantic
        // one. The word-sharing product here scores higher NUMERICALLY and
        // must still come second.
        Product source = fixtures.product("Glimberwock Ewer", "SKU-GW-SRC", new BigDecimal("50"), 5);
        Product semantic = fixtures.product("Thrimbly Decanter", "SKU-GW-S1", new BigDecimal("40"), 5);
        Product wordy = fixtures.product("Glimberwock Tureen", "SKU-GW-W1", new BigDecimal("40"), 5);

        setEmbedding(source.getId(), 1.0, 0.0, 0.0);
        setEmbedding(semantic.getId(), 0.7, 0.7141, 0.0);   // cosine 0.70
        // `wordy` gets NO embedding, so it can only arrive through the lexical
        // tier — exactly a product the sweep has not reached yet.

        assertThat(shelfOrderAmong(source.getId(), 6, List.of(semantic.getId(), wordy.getId())))
                .containsExactly(semantic.getId(), wordy.getId());
    }

    @Test
    void similar_outOfStockAndDeletedNeverAppear() {
        Product source  = fixtures.product("Fnordly Basket", "SKU-FN-SRC", new BigDecimal("50"), 5);
        Product soldOut = fixtures.product("Fnordly Liner", "SKU-FN-O1", new BigDecimal("40"), 0);
        Product gone    = fixtures.product("Fnordly Cover", "SKU-FN-D1", new BigDecimal("40"), 5);
        productRepository.delete(gone);

        assertThat(productService.similar(source.getId(), 6))
                .extracting(com.marketplace.api.dto.ProductDtos.ProductResponse::id)
                .doesNotContain(soldOut.getId(), gone.getId());
    }
}
