package com.marketplace.api.service;

import com.marketplace.api.discovery.PopularityJob;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.Review;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ReviewRepository;
import com.marketplace.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The popularity aggregates became USER-VISIBLE facts in the honest-signals
 * slice ("3 sold" on a product card means three kept sales), so the read
 * model's properties graduate from job-internal correctness to displayed
 * facts, each worth its own assertion here.
 *
 * The @Immutable ProductPopularity entity is validated implicitly by every
 * boot of this class: if its column mapping mismatches V9, ddl-auto=validate
 * fails and nothing starts. If that happens, fix the entity annotations —
 * never the migration.
 */
@Testcontainers
@SpringBootTest
class ProductSignalsTest {

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

    /** Makes @Async (view recording) run inline so nothing races the test thread. */
    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean(name = "applicationTaskExecutor")
        @Primary
        public TaskExecutor taskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired ProductService    productService;
    @Autowired OrderService      orderService;
    @Autowired OrderAdminService orderAdminService;
    @Autowired PopularityJob     popularityJob;
    @Autowired ProductRepository productRepository;
    @Autowired ReviewRepository  reviewRepository;
    @Autowired UserRepository    userRepository;
    @Autowired TestFixtures      fixtures;

    /** Direct save (bypasses purchase-verification) — aggregate input, not review-flow test. */
    private void addReview(Long productId, Long userId, int rating) {
        Review r = new Review();
        r.setProduct(productRepository.getReferenceById(productId));
        r.setUser(userRepository.getReferenceById(userId));
        r.setRating(rating);
        r.setComment("signals test");
        reviewRepository.save(r);
    }

    @Test
    void enrichedResponse_afterRealActivity() {
        Product p = fixtures.product("Signal Widget", "SKU-SIG-1", new BigDecimal("50"), 10);
        User admin = fixtures.admin("sig-admin1");

        User buyer = fixtures.customerWithCart("sig-buyer1", p, 3);
        var order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());
        addReview(p.getId(), buyer.getId(), 4);

        popularityJob.rebuild();

        ProductResponse resp = productService.get(p.getId(), null);
        assertThat(resp.soldCount()).isEqualTo(3);
        assertThat(resp.reviewCount()).isEqualTo(1);
        assertThat(resp.avgRating()).isEqualByComparingTo("4.00");
        assertThat(resp.createdAt()).isNotNull();
    }

    @Test
    void unreviewedProduct_returnsZeros_notErrors() {
        // Job deliberately NOT run after creation — no popularity row exists.
        // That's the normal state for anything created since the last hourly
        // rebuild, and zeros are the truthful answer for it.
        Product p = fixtures.product("Fresh Widget", "SKU-SIG-FRESH-1", new BigDecimal("25"), 5);

        ProductResponse resp = productService.get(p.getId(), null);

        assertThat(resp.soldCount()).isZero();
        assertThat(resp.reviewCount()).isZero();
        assertThat(resp.avgRating()).isEqualByComparingTo("0");
        assertThat(resp.createdAt()).isNotNull();
    }

    @Test
    void refundedSale_dropsOutOfSoldCount() {
        // "Kept sales" was a job-internal property; now it's a displayed fact
        // ("2 sold" means two KEPT sales), so it gets its own assertion.
        Product p = fixtures.product("Refund Widget", "SKU-SIG-REF-1", new BigDecimal("80"), 10);
        User admin = fixtures.admin("sig-admin2");

        User buyer = fixtures.customerWithCart("sig-buyer2", p, 2);
        var order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());
        orderAdminService.transition(order.id(), OrderStatus.REFUNDED, admin.getId(), "refund");

        popularityJob.rebuild();

        ProductResponse resp = productService.get(p.getId(), null);
        assertThat(resp.soldCount()).isZero();
    }

    @Test
    void catalogList_enriched() {
        // Proves the BATCH map join carries per-product numbers, not just
        // the single-get path: one active product, one untouched, same page.
        Product active = fixtures.product("List Active", "SKU-SIG-LA-1", new BigDecimal("30"), 10);
        Product quiet  = fixtures.product("List Quiet",  "SKU-SIG-LQ-1", new BigDecimal("30"), 10);
        User admin = fixtures.admin("sig-admin3");

        User buyer = fixtures.customerWithCart("sig-buyer3", active, 4);
        var order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());
        addReview(active.getId(), buyer.getId(), 5);

        popularityJob.rebuild();

        var page = productService.list(PageRequest.of(0, 200));
        ProductResponse activeResp = page.getContent().stream()
                .filter(r -> r.id().equals(active.getId())).findFirst().orElseThrow();
        ProductResponse quietResp = page.getContent().stream()
                .filter(r -> r.id().equals(quiet.getId())).findFirst().orElseThrow();

        assertThat(activeResp.soldCount()).isEqualTo(4);
        assertThat(activeResp.reviewCount()).isEqualTo(1);
        assertThat(activeResp.avgRating()).isEqualByComparingTo("5.00");
        assertThat(quietResp.soldCount()).isZero();
        assertThat(quietResp.reviewCount()).isZero();
    }

    @Test
    void catalogList_minSold_ranksBySales_andDropsUnsolds() {
        Product quiet = fixtures.product("Rank Quiet", "SKU-SIG-RK-Q", new BigDecimal("5"), 10);
        Product sold = fixtures.product("Rank Sold", "SKU-SIG-RK-S", new BigDecimal("90"), 10);
        User admin = fixtures.admin("sig-rank-admin");
        User buyer = fixtures.customerWithCart("sig-rank-buyer", sold, 3);
        var order = orderService.placeOrder(buyer.getId());
        fixtures.deliverOrder(order.id(), admin.getId());
        popularityJob.rebuild();

        var page = productService.list(null, null, null, null,
                null, 1L, null, "sales", PageRequest.of(0, 50));
        assertThat(page.getContent()).extracting(ProductResponse::id)
                .contains(sold.getId())
                .doesNotContain(quiet.getId());
        assertThat(page.getContent().getFirst().id()).isEqualTo(sold.getId());
    }

    @Test
    void catalogList_minRating_hidesUnreviewed() {
        Product quiet = fixtures.product("Rate Quiet", "SKU-SIG-RT-Q", new BigDecimal("20"), 10);
        Product rated = fixtures.product("Rate Star", "SKU-SIG-RT-S", new BigDecimal("20"), 10);
        User buyer = fixtures.customer("sig-rate-buyer");
        addReview(rated.getId(), buyer.getId(), 5);
        popularityJob.rebuild();

        var page = productService.list(null, null, null, null,
                new BigDecimal("4.5"), null, null, "rating", PageRequest.of(0, 50));
        assertThat(page.getContent()).extracting(ProductResponse::id)
                .contains(rated.getId())
                .doesNotContain(quiet.getId());
    }

    @Test
    void catalogList_priceRank_inStockOnly() {
        Product out = fixtures.product("Price Out", "SKU-SIG-PR-O", new BigDecimal("1.00"), 0);
        Product cheap = fixtures.product("Price Cheap", "SKU-SIG-PR-C", new BigDecimal("3.00"), 5);
        Product dear = fixtures.product("Price Dear", "SKU-SIG-PR-D", new BigDecimal("40.00"), 5);

        var page = productService.list(null, null, null, null,
                null, null, true, "price", PageRequest.of(0, 50));
        assertThat(page.getContent()).extracting(ProductResponse::id)
                .contains(cheap.getId(), dear.getId())
                .doesNotContain(out.getId());
        int cheapIdx = page.getContent().stream().map(ProductResponse::id).toList().indexOf(cheap.getId());
        int dearIdx = page.getContent().stream().map(ProductResponse::id).toList().indexOf(dear.getId());
        assertThat(cheapIdx).isLessThan(dearIdx);
    }
}
