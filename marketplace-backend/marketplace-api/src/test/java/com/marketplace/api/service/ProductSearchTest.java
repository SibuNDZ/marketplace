package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.Category;
import com.marketplace.api.entity.Product;
import com.marketplace.api.repository.CategoryRepository;
import com.marketplace.api.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search had NO tests at all, which is how "shoes" came to return nothing on
 * a catalogue containing a product filed under a category called Shoes.
 *
 * The tests here are mostly about the ZERO-RESULTS failure, not ranking
 * quality. On a catalogue this size an empty page for an obvious word is the
 * only search bug that actually costs a sale.
 */
@Testcontainers
@SpringBootTest
class ProductSearchTest {

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

    @Autowired ProductService      productService;
    @Autowired ProductRepository   productRepository;
    @Autowired CategoryRepository  categoryRepository;
    @Autowired TestFixtures        fixtures;
    @Autowired JdbcTemplate        jdbc;
    @Autowired TransactionTemplate txTemplate;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> search(String query) {
        return productService.list(null, null, query, null, PageRequest.of(0, 50))
                .getContent().stream().map(ProductResponse::name).toList();
    }

    /**
     * Files a product under a brand-new category with the given name.
     * hikari.auto-commit is false, so the writes go through the
     * TransactionTemplate or they are silently discarded.
     */
    private Product productInCategory(String productName, String categoryName) {
        return txTemplate.execute(status -> {
            String slug = uniq(categoryName.toLowerCase());
            jdbc.update("INSERT INTO categories (name, slug, sort_order, active) VALUES (?, ?, 0, true)",
                    categoryName, slug);
            Category category = categoryRepository.findBySlug(slug).orElseThrow();

            Product p = fixtures.product(productName, uniq("SKU-SR"), new BigDecimal("100"), 5);
            p.setCategory(category);
            return productRepository.saveAndFlush(p);
        });
    }

    // ── the reported bug ─────────────────────────────────────────────────

    @Test
    @DisplayName("regression: 'shoes' finds Jimmy Choo Pumps")
    void reportedCase_shoesFindsPumps() {
        // The exact production report: name and description both say "Pumps",
        // tags are empty, and "shoes" appeared nowhere search could see it.
        // Returned zero results before V26.
        //
        // NOTE this passes via EITHER route shipped here — the category now
        // being indexed, or the shoes->pumps synonym. That is deliberate: it
        // guards the reported symptom, not one mechanism. Removing the
        // category from the vector leaves this green, which is exactly why
        // anyCategoryNameWorks below exists to isolate that half.
        productInCategory(uniq("Jimmy Choo Pumps"), "Shoes");

        assertThat(search("shoes"))
                .withFailMessage("A product filed under a category called Shoes must be findable by 'shoes'")
                .anySatisfy(n -> assertThat(n).contains("Jimmy Choo Pumps"));
    }

    @Test
    @DisplayName("the fix generalises: any category name works, not just shoes")
    void anyCategoryNameWorks() {
        // Isolates the CATEGORY half. "Candles" has no synonym row and no
        // presence in the product text, so this can only pass through the
        // indexed category name — verified by removing it and watching this
        // test fail while the shoes regression above stayed green.
        productInCategory(uniq("Soy Wax Tumbler"), "Candles");

        assertThat(search("candles")).anySatisfy(n -> assertThat(n).contains("Soy Wax Tumbler"));
    }

    @Test
    @DisplayName("moving a product to another category re-indexes it")
    void movingCategoryReindexes() {
        Product p = productInCategory(uniq("Drifting Item"), "Hats");
        assertThat(search("hats")).anySatisfy(n -> assertThat(n).contains("Drifting Item"));

        txTemplate.execute(status -> {
            String slug = uniq("scarves");
            jdbc.update("INSERT INTO categories (name, slug, sort_order, active) VALUES ('Scarves', ?, 0, true)", slug);
            Category moved = categoryRepository.findBySlug(slug).orElseThrow();
            Product managed = productRepository.findById(p.getId()).orElseThrow();
            managed.setCategory(moved);
            return productRepository.saveAndFlush(managed);
        });

        assertThat(search("scarves")).anySatisfy(n -> assertThat(n).contains("Drifting Item"));
        // And it is no longer findable under the category it left.
        assertThat(search("hats")).noneSatisfy(n -> assertThat(n).contains("Drifting Item"));
    }

    @Test
    @DisplayName("renaming a category re-indexes every product in it")
    void renamingCategoryReindexes() {
        Product p = productInCategory(uniq("Renamed Cat Item"), "Loafers");
        assertThat(search("loafers")).anySatisfy(n -> assertThat(n).contains("Renamed Cat Item"));

        // Without the categories trigger this silently leaves every product
        // indexed under a word that no longer exists anywhere in the UI.
        txTemplate.execute(status -> jdbc.update(
                "UPDATE categories SET name = 'Moccasins' WHERE id = ?",
                p.getCategory().getId()));

        assertThat(search("moccasins")).anySatisfy(n -> assertThat(n).contains("Renamed Cat Item"));
    }

    // ── synonyms ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("footwear synonyms reach words that are not category names")
    void footwearSynonyms() {
        productInCategory(uniq("Stiletto Court Pumps"), "Shoes");

        // "heels" is not a category and not in the product text; it only
        // reaches this via the synonym rows added in V26.
        assertThat(search("heels")).anySatisfy(n -> assertThat(n).contains("Stiletto Court Pumps"));
    }

    @Test
    @DisplayName("the synonyms V21 already had still work")
    void existingSynonymsUnbroken() {
        productInCategory(uniq("Karoo Veldt Heuning"), "Pantry");

        // heuning -> honey was seeded in V21; V26 must not disturb it.
        assertThat(search("honey")).anySatisfy(n -> assertThat(n).contains("Karoo Veldt Heuning"));
    }

    // ── the parts of search that must NOT have changed ───────────────────

    @Test
    @DisplayName("the product's own name still wins")
    void nameStillMatches() {
        productInCategory(uniq("Copper Watering Can"), "Garden");
        assertThat(search("watering")).anySatisfy(n -> assertThat(n).contains("Copper Watering Can"));
    }

    @Test
    @DisplayName("a word in nothing at all still returns nothing")
    void unrelatedQueryStillEmpty() {
        productInCategory(uniq("Ceramic Mug"), "Kitchen");

        // The honest empty result. Indexing the category widens what search
        // can see; it must not turn every query into a catalogue dump.
        assertThat(search("submarine")).isEmpty();
    }
}
