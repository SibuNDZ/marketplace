package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.CategoryCount;
import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.ProductCategory;
import com.marketplace.api.entity.User;
import com.marketplace.api.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V10's category column: the real replacement for the frontend's
 * id-arithmetic category fabrication.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProductCategoryTest {

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

    @Autowired ProductService productService;
    @Autowired TestFixtures  fixtures;
    @Autowired MockMvc       mockMvc;

    private ProductResponse createProduct(String skuSuffix, ProductCategory category, User vendor) {
        String sku = "SKU-CAT-" + skuSuffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        ProductRequest req = new ProductRequest(
                "Category Test " + skuSuffix, "desc", sku,
                new BigDecimal("20.00"), 5, category);
        return productService.create(req, UserPrincipal.from(vendor));
    }

    @Test
    void createWithCategory_roundTrips() {
        User vendor = fixtures.vendor("cat-vendor1");
        ProductResponse created = createProduct("RT", ProductCategory.PANTRY, vendor);

        assertThat(created.category()).isEqualTo(ProductCategory.PANTRY);

        ProductResponse fetched = productService.get(created.id(), null);
        assertThat(fetched.category()).isEqualTo(ProductCategory.PANTRY);
    }

    @Test
    void categoryFilter_excludesOthersAndSoftDeleted() {
        User vendor = fixtures.vendor("cat-vendor2");
        ProductResponse produceA = createProduct("PA", ProductCategory.PRODUCE, vendor);
        createProduct("PB", ProductCategory.PANTRY, vendor); // different category
        ProductResponse produceC = createProduct("PC", ProductCategory.PRODUCE, vendor);
        productService.delete(produceC.id(), UserPrincipal.from(vendor)); // soft-deleted

        var page = productService.list(ProductCategory.PRODUCE, PageRequest.of(0, 200));
        List<Long> ids = page.getContent().stream().map(ProductResponse::id).toList();

        assertThat(ids).contains(produceA.id());
        assertThat(ids).doesNotContain(produceC.id());
        page.getContent().forEach(p -> assertThat(p.category()).isEqualTo(ProductCategory.PRODUCE));
    }

    @Test
    void categoryCounts_sumsCorrectly() {
        User vendor = fixtures.vendor("cat-vendor3");
        ProductResponse craftsA = createProduct("CA", ProductCategory.CRAFTS, vendor);
        createProduct("CB", ProductCategory.CRAFTS, vendor);
        ProductResponse craftsC = createProduct("CC", ProductCategory.CRAFTS, vendor);
        productService.delete(craftsC.id(), UserPrincipal.from(vendor)); // must not be counted

        long craftsCount = productService.categoryCounts().stream()
                .filter(c -> c.category() == ProductCategory.CRAFTS)
                .map(CategoryCount::count)
                .findFirst().orElse(0L);

        // >= 2, not ==, since other tests in this class also create CRAFTS-
        // adjacent rows against the same shared Testcontainers database.
        assertThat(craftsCount).isGreaterThanOrEqualTo(2L);
        assertThat(craftsA.category()).isEqualTo(ProductCategory.CRAFTS);
    }

    @Test
    void invalidCategoryValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("category", "NOTREAL"))
                .andExpect(status().isBadRequest());
    }
}
