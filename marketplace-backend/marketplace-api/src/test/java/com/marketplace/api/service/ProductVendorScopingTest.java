package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the vendor dashboard scoping bug: the dashboard used
 * the PUBLIC catalog list, so every vendor saw (and appeared able to manage)
 * the whole marketplace. listMine must return exactly the caller's products,
 * archived included, and nobody else's.
 */
@Testcontainers
@SpringBootTest
class ProductVendorScopingTest {

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

    @Autowired ProductService     productService;
    @Autowired ProductRepository  productRepository;
    @Autowired TestFixtures       fixtures;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void listMine_returnsOnlyCallersProducts_includingArchived() {
        User vendorA = fixtures.vendor("scope-vendorA");
        User vendorB = fixtures.vendor("scope-vendorB");
        Product live = fixtures.productForVendor("Scope-Live", "SKU-SCOPE-L",
                new BigDecimal("10.00"), 5, vendorA);
        Product archived = fixtures.productForVendor("Scope-Archived", "SKU-SCOPE-A",
                new BigDecimal("20.00"), 5, vendorA);
        fixtures.productForVendor("Scope-Foreign", "SKU-SCOPE-F",
                new BigDecimal("30.00"), 5, vendorB);

        new TransactionTemplate(txManager).executeWithoutResult(status ->
                productRepository.findById(archived.getId()).orElseThrow()
                        .setDeletedAt(LocalDateTime.now()));

        List<ProductResponse> mine = productService
                .listMine(vendorA.getId(), PageRequest.of(0, 100)).getContent()
                .stream().filter(p -> p.sku() != null && p.sku().startsWith("SKU-SCOPE")).toList();

        assertThat(mine).hasSize(2);
        assertThat(mine).extracting(ProductResponse::name)
                .containsExactlyInAnyOrder("Scope-Live", "Scope-Archived");
        // deletedAt drives the dashboard's Archived tab, so it must survive
        // the DTO mapping on this path.
        assertThat(mine).filteredOn(p -> p.name().equals("Scope-Archived"))
                .singleElement().satisfies(p -> assertThat(p.deletedAt()).isNotNull());
        assertThat(mine).filteredOn(p -> p.name().equals("Scope-Live"))
                .singleElement().satisfies(p -> assertThat(p.deletedAt()).isNull());

        // The other vendor sees only their own product, never vendor A's.
        List<ProductResponse> theirs = productService
                .listMine(vendorB.getId(), PageRequest.of(0, 100)).getContent()
                .stream().filter(p -> p.sku() != null && p.sku().startsWith("SKU-SCOPE")).toList();
        assertThat(theirs).extracting(ProductResponse::name).containsExactly("Scope-Foreign");

        // And the public catalog still hides the archived product.
        assertThat(productService.list(PageRequest.of(0, 100)).getContent())
                .extracting(ProductResponse::name)
                .doesNotContain("Scope-Archived");
    }
}
