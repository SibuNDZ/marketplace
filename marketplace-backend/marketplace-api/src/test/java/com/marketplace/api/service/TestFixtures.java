package com.marketplace.api.service;

import com.marketplace.api.entity.*;
import com.marketplace.api.payment.PaymentEventService;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.CategoryRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Test data builders for the order concurrency tests.
 *
 * REQUIRES_NEW on every method is deliberate: fixture data must be COMMITTED
 * before the test runs, because the concurrency test spawns real threads with
 * their own transactions — uncommitted data in the test thread's transaction
 * would be invisible to them. For the same reason the test class must NOT be
 * annotated @Transactional (rollback-based cleanup would deadlock against the
 * worker threads' locks).
 */
@Component
public class TestFixtures {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final CategoryRepository categoryRepository;
    private final PaymentEventService paymentEventService;
    private final OrderAdminService orderAdminService;

    public TestFixtures(ProductRepository productRepository,
                        UserRepository userRepository,
                        CartRepository cartRepository,
                        CategoryRepository categoryRepository,
                        PaymentEventService paymentEventService,
                        OrderAdminService orderAdminService) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.cartRepository = cartRepository;
        this.categoryRepository = categoryRepository;
        this.paymentEventService = paymentEventService;
        this.orderAdminService = orderAdminService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product product(String name, String sku, BigDecimal price, int stock) {
        Product p = new Product();
        p.setName(name);
        p.setSku(sku);
        p.setPrice(price);
        p.setStock(stock);
        p.setVendor(testVendor()); // real products always have a vendor
        // category_id is NOT NULL since V14 and the entity no longer carries a
        // default, so every fixture product needs one explicitly. 'other' is
        // the honest choice: these fixtures exist for order/stock/concurrency
        // tests that do not care where a product files.
        p.setCategory(categoryRepository.findBySlug("other").orElseThrow(
                () -> new IllegalStateException("V14 seed missing: no 'other' category")));
        return productRepository.save(p);
    }

    /**
     * Finds or creates a single shared test vendor. Find-or-create is safe
     * across REQUIRES_NEW boundaries: the second call sees the first's committed row.
     */
    private User testVendor() {
        return userRepository.findByEmail("test-vendor@test.local")
                .orElseGet(() -> {
                    User v = new User();
                    v.setEmail("test-vendor@test.local");
                    v.setFirstName("Test");
                    v.setLastName("Vendor");
                    v.setUsername("test_vendor");
                    v.setIsVerified(true);
                    v.setPassword("{noop}test-not-a-real-hash");
                    v.setRole(UserRole.VENDOR);
                    v.setBusinessName("Fixture Vendor");
                    return userRepository.save(v);
                });
    }

    /** Like {@link #product} but for a caller-chosen vendor (multi-vendor scenarios). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product productForVendor(String name, String sku, BigDecimal price, int stock, User vendor) {
        Product p = new Product();
        p.setName(name);
        p.setSku(sku);
        p.setPrice(price);
        p.setStock(stock);
        p.setVendor(userRepository.getReferenceById(vendor.getId()));
        p.setCategory(categoryRepository.findBySlug("other").orElseThrow(
                () -> new IllegalStateException("V14 seed missing: no 'other' category")));
        return productRepository.save(p);
    }

    /** Cart holding one unit of each given product — multi-vendor order fixtures. */
    /**
     * Refills an existing customer's cart, for tests that need a SECOND
     * order from the same buyer (placeOrder empties the cart).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addToCart(Long userId, Product product, int quantity) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    Cart c = new Cart();
                    c.setUser(userRepository.getReferenceById(userId));
                    return c;
                });
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProduct(productRepository.getReferenceById(product.getId()));
        item.setQuantity(quantity);
        cart.getItems().add(item);
        cartRepository.save(cart);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User customerWithCartOf(String username, Product... products) {
        User user = persistUser(username, UserRole.CUSTOMER);

        Cart cart = new Cart();
        cart.setUser(user);
        for (Product product : products) {
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setProduct(productRepository.getReferenceById(product.getId()));
            item.setQuantity(1);
            cart.getItems().add(item);
        }
        cartRepository.save(cart);
        return user;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User customerWithCart(String username, Product product, int quantity) {
        User user = persistUser(username, UserRole.CUSTOMER);

        Cart cart = new Cart();
        cart.setUser(user);

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(quantity);
        cart.getItems().add(item);

        cartRepository.save(cart);
        return user;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User vendor(String username) {
        return userRepository.findByEmail(username + "@test.local")
                .orElseGet(() -> persistUser(username, UserRole.VENDOR));
    }

    /** Customer with no cart — for tests that don't need to place an order. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User customer(String username) {
        return userRepository.findByEmail(username + "@test.local")
                .orElseGet(() -> persistUser(username, UserRole.CUSTOMER));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User admin(String username) {
        return userRepository.findByEmail(username + "@test.local")
                .orElseGet(() -> persistUser(username, UserRole.ADMIN));
    }

    /**
     * Drives a PENDING order all the way to DELIVERED via the full payment path:
     * PENDING -> PAID (webhook) -> SHIPPED (admin) -> DELIVERED (admin).
     * Each step runs in its own transaction, matching real runtime behavior.
     */
    public void deliverOrder(Long orderId, Long adminId) {
        paymentEventService.handleCheckoutCompleted(orderId, "Stripe"); // PENDING -> PAID
        orderAdminService.transition(orderId, OrderStatus.SHIPPED,    adminId, "Shipped");
        orderAdminService.transition(orderId, OrderStatus.DELIVERED,  adminId, "Delivered");
    }

    private User persistUser(String username, UserRole role) {
        User u = new User();
        u.setEmail(username + "@test.local");
        u.setFirstName(username);
        u.setLastName("test");
        u.setUsername(sanitiseUsername(username));
        // Fixture users are used by tests that need a working session; they
        // never go through the email flow, so they start verified.
        u.setIsVerified(true);
        u.setPassword("{noop}test-not-a-real-hash");
        u.setRole(role);
        // Vendors trade under a business name (V19); fixtures mirror real
        // accounts so listing-attribution tests exercise the real path.
        if (role == UserRole.VENDOR) {
            u.setBusinessName(username + " Trading");
        }
        return userRepository.save(u);
    }

    /**
     * users.username is NOT NULL UNIQUE with a [a-z0-9_] shape. Test callers
     * pass free-form tags containing hyphens and dots, so normalise rather
     * than making every call site care.
     */
    private static String sanitiseUsername(String raw) {
        String cleaned = raw.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        cleaned = cleaned.substring(0, Math.min(cleaned.length(), 30));
        return cleaned.length() >= 3 ? cleaned : cleaned + "_u";
    }
}
