package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.dto.ProductDtos.VariantRequest;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ProductVariantService.DuplicateVariantLabelException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Variants, step 1: the read model and vendor CRUD. Nothing here touches
 * the buy path — that is step 2 (product-variants.md).
 *
 * The two rules under test are the stock/price delegation (a product with
 * variants reports summed stock and the cheapest price) and ownership,
 * which is the same class of boundary as the vendor-dashboard scoping bug.
 */
@Testcontainers
@SpringBootTest
class ProductVariantTest {

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

    @Autowired ProductVariantService variantService;
    @Autowired ProductService        productService;
    @Autowired TestFixtures          fixtures;

    private UserPrincipal principal(User u) {
        return new UserPrincipal(u.getId(), u.getEmail(), u.getPassword(), u.getRole().name(), true);
    }

    private static VariantRequest variant(String label, String price, int stock) {
        return new VariantRequest(label, null, new BigDecimal(price), stock, 0);
    }

    @Test
    void productWithoutVariants_behavesExactlyAsBefore() {
        User vendor = fixtures.vendor("pv-plain");
        Product p = fixtures.productForVendor("PV-Plain", "SKU-PV-P",
                new BigDecimal("100.00"), 7, vendor);

        ProductResponse response = productService.get(p.getId(), null);
        assertThat(response.variants()).isEmpty();
        assertThat(response.stock()).isEqualTo(7);
        assertThat(response.price()).isEqualByComparingTo("100.00");
    }

    @Test
    void productWithVariants_reportsSummedStockAndCheapestPrice() {
        User vendor = fixtures.vendor("pv-sum");
        Product p = fixtures.productForVendor("PV-Sum", "SKU-PV-S",
                new BigDecimal("999.00"), 3, vendor);

        variantService.add(p.getId(), variant("Black", "120.00", 4), principal(vendor));
        variantService.add(p.getId(), variant("Orange", "150.00", 6), principal(vendor));

        ProductResponse response = productService.get(p.getId(), null);
        assertThat(response.variants()).hasSize(2);
        // Summed, so "in stock" means some option is buyable...
        assertThat(response.stock()).isEqualTo(10);
        // ...and the cheapest, so a card can honestly read "from R120".
        assertThat(response.price()).isEqualByComparingTo("120.00");
        // The product's own columns are deliberately ignored, not deleted.
        assertThat(response.stock()).isNotEqualTo(3);
    }

    @Test
    void computedStockAndPrice_alsoApplyOnListings_notJustDetail() {
        User vendor = fixtures.vendor("pv-list");
        Product p = fixtures.productForVendor("PV-List", "SKU-PV-L",
                new BigDecimal("999.00"), 3, vendor);
        variantService.add(p.getId(), variant("Small", "80.00", 2), principal(vendor));
        variantService.add(p.getId(), variant("Large", "95.00", 5), principal(vendor));

        ProductResponse fromList = productService.listMine(vendor.getId(), PageRequest.of(0, 50))
                .getContent().stream().filter(r -> r.id().equals(p.getId()))
                .findFirst().orElseThrow();

        assertThat(fromList.stock()).isEqualTo(7);
        assertThat(fromList.price()).isEqualByComparingTo("80.00");
        assertThat(fromList.variants()).extracting(v -> v.label())
                .containsExactly("Small", "Large");
    }

    @Test
    void duplicateLabelOnSameProduct_isRejected_butFineOnAnother() {
        User vendor = fixtures.vendor("pv-dupe");
        Product a = fixtures.productForVendor("PV-DupeA", "SKU-PV-DA",
                new BigDecimal("10.00"), 1, vendor);
        Product b = fixtures.productForVendor("PV-DupeB", "SKU-PV-DB",
                new BigDecimal("10.00"), 1, vendor);

        variantService.add(a.getId(), variant("Black", "10.00", 1), principal(vendor));
        assertThatThrownBy(() -> variantService.add(a.getId(), variant("black", "12.00", 1), principal(vendor)))
                .isInstanceOf(DuplicateVariantLabelException.class);
        // Same label on a DIFFERENT product is normal.
        variantService.add(b.getId(), variant("Black", "10.00", 1), principal(vendor));
        assertThat(productService.get(b.getId(), null).variants()).hasSize(1);
    }

    @Test
    void anotherVendorCannotTouchYourVariants_and404sRatherThan403() {
        User owner = fixtures.vendor("pv-owner");
        User stranger = fixtures.vendor("pv-stranger");
        Product p = fixtures.productForVendor("PV-Owned", "SKU-PV-O",
                new BigDecimal("50.00"), 5, owner);
        ProductResponse withVariant = variantService.add(
                p.getId(), variant("Red", "50.00", 2), principal(owner));
        Long variantId = withVariant.variants().get(0).id();

        // 404, not 403: an id you do not own must be indistinguishable from
        // one that does not exist, or this becomes a catalogue oracle.
        assertThatThrownBy(() -> variantService.add(p.getId(), variant("Blue", "50.00", 1), principal(stranger)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> variantService.update(p.getId(), variantId, variant("Blue", "50.00", 1), principal(stranger)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> variantService.delete(p.getId(), variantId, principal(stranger)))
                .isInstanceOf(ProductNotFoundException.class);

        // Owner's data is untouched by the attempts.
        assertThat(productService.get(p.getId(), null).variants()).hasSize(1);
    }

    @Test
    void updateAndDelete_keepTheProductConsistent() {
        User vendor = fixtures.vendor("pv-edit");
        Product p = fixtures.productForVendor("PV-Edit", "SKU-PV-E",
                new BigDecimal("50.00"), 5, vendor);
        ProductResponse afterAdd = variantService.add(p.getId(), variant("One", "30.00", 3), principal(vendor));
        Long id = afterAdd.variants().get(0).id();
        variantService.add(p.getId(), variant("Two", "45.00", 1), principal(vendor));

        ProductResponse afterUpdate = variantService.update(
                p.getId(), id, variant("One", "20.00", 10), principal(vendor));
        assertThat(afterUpdate.price()).isEqualByComparingTo("20.00");
        assertThat(afterUpdate.stock()).isEqualTo(11);

        ProductResponse afterDelete = variantService.delete(p.getId(), id, principal(vendor));
        assertThat(afterDelete.variants()).hasSize(1);
        assertThat(afterDelete.price()).isEqualByComparingTo("45.00");
        assertThat(afterDelete.stock()).isEqualTo(1);
    }
}
