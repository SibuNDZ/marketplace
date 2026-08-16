package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.ProductExceptions.CompareAtPricingPausedException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ProductVariantRepository;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compare-at pricing, PAUSED on 2026-08-13 before any vendor set one.
 *
 * The validation below is retained and still correct — it is what the
 * feature will need again — but the service now refuses any NEW or CHANGED
 * originalPrice outright, so the acceptance tests assert the pause instead
 * of a successful write.
 *
 * Why it was paused: the guardrail only proved originalPrice > price, which
 * says nothing about whether that price was ever charged. Every other
 * trust-sensitive number on this platform is DERIVED from something the
 * system recorded (sold count from kept orders, reviews gated on delivery,
 * ratings excluding refunds); this was the sole exception, taken on the
 * self-report of the party with an incentive to inflate it. The replacement
 * is a price-history-derived "was" price — a real recorded drop, eligible
 * only after a genuine minimum duration, in the shape the EU Omnibus
 * Directive mandates for exactly this abuse pattern.
 */
@Testcontainers
@SpringBootTest
class ComparePriceTest {

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

    @Autowired ProductService          productService;
    @Autowired ProductRepository       productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired TestFixtures            fixtures;
    @Autowired Validator               validator;

    private static int seq = 0;
    private static String uniq(String base) { return base + "-" + (++seq); }

    private ProductRequest request(BigDecimal price, BigDecimal originalPrice) {
        return new ProductRequest(uniq("Sale Item"), "desc", uniq("SKU-CP"),
                price, originalPrice, 10, "pantry", false, List.of());
    }

    // ── what the system refuses ──────────────────────────────────────────

    @Test
    @DisplayName("a 'was' price equal to the price is rejected: that is 0% off")
    void equalPriceRejected() {
        Set<ConstraintViolation<ProductRequest>> v =
                validator.validate(request(new BigDecimal("100.00"), new BigDecimal("100.00")));
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .contains("The original price must be higher than the selling price");
    }

    @Test
    @DisplayName("a 'was' price below the price is rejected: that is a negative discount")
    void lowerPriceRejected() {
        Set<ConstraintViolation<ProductRequest>> v =
                validator.validate(request(new BigDecimal("100.00"), new BigDecimal("80.00")));
        assertThat(v).isNotEmpty();
    }

    @Test
    @DisplayName("the database refuses it too, so no write path can get around the DTO")
    void databaseConstraintIsTheBackstop() {
        Product p = fixtures.product(uniq("Constraint"), uniq("SKU-CC"), new BigDecimal("100"), 5);
        p.setOriginalPrice(new BigDecimal("50.00"));   // below price — invalid

        // V23's CHECK is what protects an import script or a hand-run UPDATE
        // that never passes through ProductRequest validation.
        assertThatThrownBy(() -> productRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── what it allows ───────────────────────────────────────────────────

    @Test
    @DisplayName("PAUSED: even an arithmetically valid markdown is refused")
    void genuineDiscountNowRefused() {
        // Still passes bean validation — the rule is sound and stays for the
        // derived version. The service is what stops it now.
        assertThat(validator.validate(request(new BigDecimal("80.00"), new BigDecimal("100.00"))))
                .isEmpty();

        User vendor = fixtures.vendor(uniq("cp-vendor"));
        assertThatThrownBy(() -> productService.create(
                request(new BigDecimal("80.00"), new BigDecimal("100.00")),
                UserPrincipal.from(vendor)))
                .isInstanceOf(CompareAtPricingPausedException.class);
    }

    @Test
    @DisplayName("omitting the field is normal, and the pause costs it nothing")
    void nullMeansNoSale() {
        assertThat(validator.validate(request(new BigDecimal("80.00"), null))).isEmpty();

        User vendor = fixtures.vendor(uniq("cp-vendor"));
        ProductResponse created = productService.create(
                request(new BigDecimal("80.00"), null),
                UserPrincipal.from(vendor));

        assertThat(created.originalPrice()).isNull();
    }

    // ── the interaction that would have shipped a lie ────────────────────

    @Test
    @DisplayName("with variants the 'was' price is suppressed, not shown against a variant price")
    void variantsSuppressOriginalPrice() {
        Product p = fixtures.product(uniq("Variant Sale"), uniq("SKU-VS"), new BigDecimal("100"), 0);
        p.setOriginalPrice(new BigDecimal("150.00"));
        productRepository.save(p);

        // Options priced ABOVE both the product price and its "was" price.
        // The card shows the cheapest option (200), so leaving originalPrice
        // in place would strike through 150 above 200 and advertise a price
        // increase as a saving.
        for (String label : List.of("Small", "Large")) {
            ProductVariant v = new ProductVariant();
            v.setProduct(p);
            v.setLabel(label);
            v.setSku(uniq("SKU-VS-" + label));
            v.setPrice(new BigDecimal(label.equals("Small") ? "200.00" : "300.00"));
            v.setStockQuantity(5);
            variantRepository.save(v);
        }

        // Through the transactional read path, not toResponse on a detached
        // entity — the vendor association is lazy and would not initialise.
        ProductResponse response = productService.get(p.getId(), null);

        assertThat(response.price()).isEqualByComparingTo("200.00");   // cheapest option
        assertThat(response.originalPrice()).isNull();                 // and no fake saving
    }
}
