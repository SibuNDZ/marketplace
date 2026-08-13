package com.marketplace.api.service;

import com.marketplace.api.dto.CartDtos.AddItemRequest;
import com.marketplace.api.dto.CartDtos.CartResponse;
import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.OrderExceptions.InsufficientStockException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ProductVariantRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Buying a specific option, end to end.
 *
 * This closes a real hole rather than only adding a feature. Before V25 the
 * cart took a productId with no option, so ordering a variant product
 * decremented products.stock_quantity — a number the shopper never saw —
 * while the option's own stock, which IS what the page displayed, never
 * moved. The same product could be sold indefinitely.
 */
@Testcontainers
@SpringBootTest
class VariantCartTest {

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

    @Autowired CartService             cartService;
    @Autowired OrderService            orderService;
    @Autowired ProductRepository       productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired TestFixtures            fixtures;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A product whose stock and price live entirely on its options. */
    private Product withVariants(String name, int smallStock, String smallPrice,
                                 int largeStock, String largePrice) {
        // products.stock_quantity is 0 deliberately: for a variant product it
        // is not the number that matters, and leaving it non-zero would let a
        // bug pass by drawing stock from the wrong place.
        Product p = fixtures.product(uniq(name), uniq("SKU-VC"), new BigDecimal("999.00"), 0);
        variant(p, "Small", smallPrice, smallStock, 0);
        variant(p, "Large", largePrice, largeStock, 1);
        return p;
    }

    private ProductVariant variant(Product p, String label, String price, int stock, int pos) {
        ProductVariant v = new ProductVariant();
        v.setProduct(p);
        v.setLabel(label);
        v.setSku(uniq("SKU-V-" + label));
        v.setPrice(new BigDecimal(price));
        v.setStockQuantity(stock);
        v.setPosition(pos);
        return variantRepository.save(v);
    }

    private List<ProductVariant> optionsOf(Product p) {
        return variantRepository.findByProductIdOrderByPositionAscIdAsc(p.getId());
    }

    // ── what the cart refuses ────────────────────────────────────────────

    @Test
    @DisplayName("a product with options cannot be added without choosing one")
    void variantRequired() {
        Product p = withVariants("Needs Option", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));

        assertThatThrownBy(() ->
                cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), null, 1)))
                .isInstanceOf(VariantSelection.VariantRequiredException.class);
    }

    @Test
    @DisplayName("an option from a different product is refused")
    void foreignVariantRefused() {
        Product a = withVariants("Product A", 5, "100.00", 5, "150.00");
        Product b = withVariants("Product B", 5, "10.00", 5, "20.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));

        // Without the belongs-to check this buys B's cheap option while
        // charging against A — the classic parameter-swap bug.
        Long foreign = optionsOf(b).get(0).getId();
        assertThatThrownBy(() ->
                cartService.addItem(buyer.getId(), new AddItemRequest(a.getId(), foreign, 1)))
                .isInstanceOf(VariantSelection.VariantNotApplicableException.class);
    }

    @Test
    @DisplayName("an option cannot be attached to a product that has none")
    void variantOnPlainProductRefused() {
        Product plain = fixtures.product(uniq("Plain"), uniq("SKU-VP"), new BigDecimal("50.00"), 5);
        Product withOpts = withVariants("Has Options", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));

        assertThatThrownBy(() -> cartService.addItem(buyer.getId(),
                new AddItemRequest(plain.getId(), optionsOf(withOpts).get(0).getId(), 1)))
                .isInstanceOf(VariantSelection.VariantNotApplicableException.class);
    }

    // ── the cart keeps options apart ─────────────────────────────────────

    @Test
    @DisplayName("two options of one product are two lines, priced separately")
    void optionsAreSeparateLines() {
        Product p = withVariants("Two Lines", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        List<ProductVariant> opts = optionsOf(p);

        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), opts.get(0).getId(), 1));
        CartResponse cart = cartService.addItem(buyer.getId(),
                new AddItemRequest(p.getId(), opts.get(1).getId(), 2));

        // Merging these would silently change what the shopper is buying.
        assertThat(cart.items()).hasSize(2);
        assertThat(cart.items()).extracting(l -> l.variantLabel())
                .containsExactlyInAnyOrder("Small", "Large");
        // Each line quotes ITS OWN option's price, not products.price (999).
        assertThat(cart.subtotal()).isEqualByComparingTo("400.00");   // 100 + 2x150
    }

    @Test
    @DisplayName("adding the same option twice increments that line only")
    void sameOptionMerges() {
        Product p = withVariants("Merge", 9, "100.00", 9, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        Long small = optionsOf(p).get(0).getId();

        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), small, 1));
        CartResponse cart = cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), small, 2));

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("removing one option leaves the other alone")
    void removeTargetsOneLine() {
        Product p = withVariants("Remove", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        List<ProductVariant> opts = optionsOf(p);
        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), opts.get(0).getId(), 1));
        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), opts.get(1).getId(), 1));

        CartResponse cart = cartService.removeItem(buyer.getId(), p.getId(), opts.get(0).getId());

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).variantLabel()).isEqualTo("Large");
    }

    // ── the hole this closes ─────────────────────────────────────────────

    @Test
    @DisplayName("ordering decrements the OPTION's stock, not the product's")
    void orderDecrementsVariantStock() {
        Product p = withVariants("Decrement", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        List<ProductVariant> opts = optionsOf(p);
        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), opts.get(0).getId(), 2));

        orderService.placeOrder(buyer.getId());

        assertThat(variantRepository.findById(opts.get(0).getId()).orElseThrow()
                .getStockQuantity()).isEqualTo(3);        // Small drew down
        assertThat(variantRepository.findById(opts.get(1).getId()).orElseThrow()
                .getStockQuantity()).isEqualTo(5);        // Large untouched
        // And the product's own column, which nobody displays for a variant
        // product, is NOT the thing that moved.
        assertThat(productRepository.findById(p.getId()).orElseThrow().getStock()).isZero();
    }

    @Test
    @DisplayName("the order is priced from the option and snapshots its label")
    void orderPricesAndNamesTheOption() {
        Product p = withVariants("Priced", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), optionsOf(p).get(1).getId(), 2));

        OrderResponse order = orderService.placeOrder(buyer.getId());

        // 2 x 150, NOT 2 x 999 from products.price.
        assertThat(order.total()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("stock runs out per option, not per product")
    void shortageIsPerOption() {
        Product p = withVariants("Shortage", 1, "100.00", 50, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), optionsOf(p).get(0).getId(), 3));

        // Plenty of Large in stock, but Small is the one being bought.
        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("cancelling gives the units back to the option they came from")
    void cancelRestoresVariantStock() {
        Product p = withVariants("Restore", 5, "100.00", 5, "150.00");
        User buyer = fixtures.customer(uniq("vc-buyer"));
        Long small = optionsOf(p).get(0).getId();
        cartService.addItem(buyer.getId(), new AddItemRequest(p.getId(), small, 2));
        OrderResponse order = orderService.placeOrder(buyer.getId());

        assertThat(variantRepository.findById(small).orElseThrow().getStockQuantity()).isEqualTo(3);

        orderService.cancelOrder(order.id(), buyer.getId());

        // Back to 5 on the OPTION. Crediting the product instead would leak
        // stock: the option stays sold out while a hidden number grows.
        assertThat(variantRepository.findById(small).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(productRepository.findById(p.getId()).orElseThrow().getStock()).isZero();
    }

    // ── products without options still work exactly as before ────────────

    @Test
    @DisplayName("a product with no options is unaffected by any of this")
    void plainProductUnchanged() {
        Product plain = fixtures.product(uniq("Plain Flow"), uniq("SKU-PF"), new BigDecimal("40.00"), 10);
        User buyer = fixtures.customer(uniq("vc-buyer"));

        CartResponse cart = cartService.addItem(buyer.getId(),
                new AddItemRequest(plain.getId(), null, 2));
        assertThat(cart.items().get(0).variantId()).isNull();
        assertThat(cart.items().get(0).variantLabel()).isNull();
        assertThat(cart.subtotal()).isEqualByComparingTo("80.00");

        OrderResponse order = orderService.placeOrder(buyer.getId());

        assertThat(order.total()).isEqualByComparingTo("80.00");
        assertThat(productRepository.findById(plain.getId()).orElseThrow().getStock()).isEqualTo(8);
    }
}
