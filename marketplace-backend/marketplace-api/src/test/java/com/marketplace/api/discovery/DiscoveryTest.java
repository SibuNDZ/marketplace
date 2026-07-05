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
        jdbc.update("UPDATE product_views SET viewed_at = ? WHERE id = ?", backdated, oldId);

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
}
