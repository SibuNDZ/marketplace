package com.marketplace.api.service;

import com.marketplace.api.dto.CategoryDtos.CategoryNode;
import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.CategoryExceptions.CategoryNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V14's category tree, replacing V10's five-value enum.
 *
 * The cases that matter here are the ones the enum could not express at
 * all: a top-level filter that has to reach its children, and handmade as
 * an axis crossing every category.
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

    @Autowired ProductService  productService;
    @Autowired CategoryService categoryService;
    @Autowired TestFixtures    fixtures;
    @Autowired MockMvc         mockMvc;

    private ProductResponse createProduct(String skuSuffix, String categorySlug,
                                          boolean handmade, List<String> tags, User vendor) {
        String sku = "SKU-CAT-" + skuSuffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        ProductRequest req = new ProductRequest(
                "Category Test " + skuSuffix, "desc", sku,
                // null originalPrice = not on sale, the default for every
                // listing that has not been deliberately marked down.
                new BigDecimal("20.00"), null, 5, categorySlug, handmade, tags);
        return productService.create(req, UserPrincipal.from(vendor));
    }

    @Test
    void createWithSubcategory_roundTripsWithParent() {
        User vendor = fixtures.vendor("cat-vendor1");
        ProductResponse created = createProduct("RT", "jewellery", false, List.of(), vendor);

        assertThat(created.categorySlug()).isEqualTo("jewellery");
        assertThat(created.parentCategorySlug()).isEqualTo("fashion");

        ProductResponse fetched = productService.get(created.id(), null);
        assertThat(fetched.categorySlug()).isEqualTo("jewellery");
        assertThat(fetched.categoryName()).isEqualTo("Jewellery");
    }

    /**
     * The whole point of the tree. Filtering on the ROOT must return a
     * product filed on a CHILD — under the old enum this could not even be
     * asked, and getting it wrong shows an empty Fashion page while
     * jewellery sits in it.
     */
    @Test
    void topLevelFilter_includesSubcategoryProducts() {
        User vendor = fixtures.vendor("cat-vendor2");
        ProductResponse necklace = createProduct("JW", "jewellery", false, List.of(), vendor);
        ProductResponse apple    = createProduct("AP", "produce", false, List.of(), vendor);

        var page = productService.list("fashion", null, PageRequest.of(0, 200));
        List<Long> ids = page.getContent().stream().map(ProductResponse::id).toList();

        assertThat(ids).contains(necklace.id());
        assertThat(ids).doesNotContain(apple.id());
    }

    @Test
    void subcategoryFilter_doesNotLeakSiblings() {
        User vendor = fixtures.vendor("cat-vendor3");
        ProductResponse necklace = createProduct("J2", "jewellery", false, List.of(), vendor);
        ProductResponse shoe     = createProduct("SH", "shoes", false, List.of(), vendor);

        var page = productService.list("jewellery", null, PageRequest.of(0, 200));
        List<Long> ids = page.getContent().stream().map(ProductResponse::id).toList();

        assertThat(ids).contains(necklace.id());
        assertThat(ids).doesNotContain(shoe.id());
    }

    @Test
    void categoryFilter_excludesSoftDeleted() {
        User vendor = fixtures.vendor("cat-vendor4");
        ProductResponse keep = createProduct("K1", "produce", false, List.of(), vendor);
        ProductResponse gone = createProduct("K2", "produce", false, List.of(), vendor);
        productService.delete(gone.id(), UserPrincipal.from(vendor));

        var page = productService.list("produce", null, PageRequest.of(0, 200));
        List<Long> ids = page.getContent().stream().map(ProductResponse::id).toList();

        assertThat(ids).contains(keep.id());
        assertThat(ids).doesNotContain(gone.id());
    }

    /** handmade crosses categories — the reason it is a flag and not one. */
    @Test
    void handmadeFilter_crossesCategories() {
        User vendor = fixtures.vendor("cat-vendor5");
        ProductResponse handmadeNecklace = createProduct("HM1", "jewellery", true, List.of(), vendor);
        ProductResponse handmadeBowl     = createProduct("HM2", "decor",     true, List.of(), vendor);
        ProductResponse factoryShoe      = createProduct("HM3", "shoes",     false, List.of(), vendor);

        var page = productService.list(null, true, PageRequest.of(0, 500));
        List<Long> ids = page.getContent().stream().map(ProductResponse::id).toList();

        assertThat(ids).contains(handmadeNecklace.id(), handmadeBowl.id());
        assertThat(ids).doesNotContain(factoryShoe.id());
        page.getContent().forEach(p -> assertThat(p.handmade()).isTrue());
    }

    @Test
    void categoryAndHandmade_combine() {
        User vendor = fixtures.vendor("cat-vendor6");
        ProductResponse handmadeNecklace = createProduct("CB1", "jewellery", true, List.of(), vendor);
        ProductResponse plainNecklace    = createProduct("CB2", "jewellery", false, List.of(), vendor);
        ProductResponse handmadeBowl     = createProduct("CB3", "decor",     true, List.of(), vendor);

        var page = productService.list("fashion", true, PageRequest.of(0, 500));
        List<Long> ids = page.getContent().stream().map(ProductResponse::id).toList();

        assertThat(ids).contains(handmadeNecklace.id());
        assertThat(ids).doesNotContain(plainNecklace.id(), handmadeBowl.id());
    }

        @Test
        void nameSearch_matchesProductOrVendor_caseInsensitively() {
        User vendor = fixtures.vendor("searchable-vendor");
        ProductResponse lantern = createProduct(
            "Copper-Garden-Lantern", "decor", false, List.of(), vendor);

        var byProduct = productService.list(
            null, null, "GARDEN-LANTERN", PageRequest.of(0, 20));
        var byVendor = productService.list(
            null, null, vendor.getUsername().toUpperCase(), PageRequest.of(0, 20));

        assertThat(byProduct.getContent()).extracting(ProductResponse::id).contains(lantern.id());
        assertThat(byVendor.getContent()).extracting(ProductResponse::id).contains(lantern.id());
        }

    @Test
    void tags_areNormalisedAndDeduplicated() {
        User vendor = fixtures.vendor("cat-vendor7");
        ProductResponse p = createProduct(
                "TG", "produce", false, List.of(" Vegan ", "vegan", "ORGANIC", ""), vendor);

        assertThat(p.tags()).containsExactly("vegan", "organic");
    }

    /**
     * Subtree counts, not direct ones: a root showing 0 while its child
     * shows 3 is the number that makes people distrust the page.
     */
    @Test
    void tree_rollsChildCountsIntoParent() {
        User vendor = fixtures.vendor("cat-vendor8");
        createProduct("TR", "ceramics", false, List.of(), vendor);

        CategoryNode artAndCrafts = categoryService.tree(true).stream()
                .filter(n -> n.slug().equals("art-and-crafts"))
                .findFirst().orElseThrow();

        assertThat(artAndCrafts.productCount()).isGreaterThanOrEqualTo(1L);
        assertThat(artAndCrafts.children())
                .extracting(CategoryNode::slug)
                .contains("ceramics", "art-and-prints");
    }

    @Test
    void tree_hasThirteenTopLevelCategories() {
        assertThat(categoryService.tree(true))
                .extracting(CategoryNode::slug)
                .containsExactly("produce", "pantry", "fashion",
                        "clothing", "footwear", "accessories", "jewellery-collections", "sport",
                        "beauty-and-personal-care",
                        "home-and-living", "art-and-crafts", "kids-and-baby", "other");
    }

    @Test
    void unknownCategorySlug_is404NotEmptyPage() throws Exception {
        assertThatThrownBy(() -> productService.list("not-a-category", null, PageRequest.of(0, 20)))
                .isInstanceOf(CategoryNotFoundException.class);

        mockMvc.perform(get("/api/v1/products").param("category", "not-a-category"))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryTreeEndpoint_isPublicAndHidesEmptyCategoriesByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productCount == 0)]").isEmpty());

        mockMvc.perform(get("/api/v1/categories").param("includeEmpty", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productCount == 0)]").isNotEmpty());

        mockMvc.perform(get("/api/v1/categories/options")).andExpect(status().isOk());
    }
}
