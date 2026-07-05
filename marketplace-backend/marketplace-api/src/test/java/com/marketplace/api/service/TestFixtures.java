package com.marketplace.api.service;

import com.marketplace.api.entity.*;
import com.marketplace.api.repository.CartRepository;
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

    public TestFixtures(ProductRepository productRepository,
                        UserRepository userRepository,
                        CartRepository cartRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.cartRepository = cartRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product product(String name, String sku, BigDecimal price, int stock) {
        Product p = new Product();
        p.setName(name);
        p.setSku(sku);
        p.setPrice(price);
        p.setStock(stock);
        p.setVendor(testVendor()); // real products always have a vendor
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
                    v.setPassword("{noop}test-not-a-real-hash");
                    v.setRole(UserRole.VENDOR);
                    return userRepository.save(v);
                });
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

    private User persistUser(String username, UserRole role) {
        User u = new User();
        u.setEmail(username + "@test.local");
        u.setFirstName(username);
        u.setLastName("test");
        u.setPassword("{noop}test-not-a-real-hash");
        u.setRole(role);
        return userRepository.save(u);
    }
}
