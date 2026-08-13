package com.marketplace.api.service;

import com.marketplace.api.dto.CartDtos.CartResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cart response had NO image field, which is why both cart surfaces fell
 * back to rendering a stock photo keyed on product id — a shopper reviewing
 * their own cart was shown pictures of things they had not chosen.
 *
 * There were no cart service tests at all before this, so the response shape
 * was entirely unguarded.
 */
@Testcontainers
@SpringBootTest
class CartImageTest {

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

    @Autowired TestFixtures       fixtures;
    @Autowired CartService        cartService;
    @Autowired ProductRepository  productRepository;

    private static int seq = 0;
    private static String uniq(String base) { return base + "-" + (++seq); }

    @Test
    @DisplayName("a cart line carries the vendor's real photo URL")
    void cartLineCarriesImageUrl() {
        Product p = fixtures.product(uniq("Cart Img"), uniq("SKU-CI"), new BigDecimal("50"), 10);
        p.setImageKey("products/" + p.getId() + "/abc-123.jpg");
        productRepository.save(p);

        User buyer = fixtures.customerWithCart(uniq("cart-img-buyer"), p, 2);
        CartResponse cart = cartService.getCart(buyer.getId());

        assertThat(cart.items()).hasSize(1);
        // Derived from the key exactly as ProductResponse does it, so the two
        // surfaces can never disagree about where an image lives.
        assertThat(cart.items().get(0).imageUrl())
                .isEqualTo("https://images.erestyu.com/products/" + p.getId() + "/abc-123.jpg");
    }

    @Test
    @DisplayName("no photo means null, never a stand-in")
    void missingImageIsNull() {
        Product p = fixtures.product(uniq("Cart NoImg"), uniq("SKU-CN"), new BigDecimal("50"), 10);
        User buyer = fixtures.customerWithCart(uniq("cart-noimg-buyer"), p, 1);

        CartResponse cart = cartService.getCart(buyer.getId());

        // Null is the whole point. The frontend renders an empty well for it;
        // an unfilled square reads as "no picture", a random stock photo reads
        // as "wrong order".
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).imageUrl()).isNull();
    }
}
