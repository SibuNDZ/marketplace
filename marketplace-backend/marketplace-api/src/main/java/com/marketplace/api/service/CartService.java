package com.marketplace.api.service;

import com.marketplace.api.dto.CartDtos.AddItemRequest;
import com.marketplace.api.dto.CartDtos.CartResponse;
import com.marketplace.api.dto.CartDtos.CartResponse.CartLine;
import com.marketplace.api.entity.Cart;
import com.marketplace.api.entity.CartItem;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ProductVariantRepository;
import com.marketplace.api.repository.UserRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Cart management. The cart does NOT validate stock — adding more than
 * available succeeds. Stock enforcement is OrderService's job (under locks).
 * Enforcing here would give false comfort: stock can change between
 * add-to-cart and checkout anyway. CartLine.availableStock lets the UI warn
 * without pretending the cart is a reservation.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final com.marketplace.api.storage.ObjectStorageService storage;
    private final com.marketplace.api.repository.ProductImageRepository imageRepository;
    private final ProductVariantRepository variantRepository;

    public CartService(CartRepository cartRepository,
                       ProductRepository productRepository,
                       UserRepository userRepository,
                       com.marketplace.api.storage.ObjectStorageService storage,
                       com.marketplace.api.repository.ProductImageRepository imageRepository,
                       ProductVariantRepository variantRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.imageRepository = imageRepository;
        this.variantRepository = variantRepository;
    }

    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        return cartRepository.findWithItemsByUserId(userId)
                .map(this::toResponse)
                .orElseGet(() -> new CartResponse(List.of(), BigDecimal.ZERO));
    }

    /**
     * Upsert: adding a product already in the cart increments its quantity
     * rather than creating a duplicate line.
     */
    @Transactional
    public CartResponse addItem(Long userId, AddItemRequest request) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        // Resolve and validate the option BEFORE touching the cart, so a bad
        // request never half-mutates it.
        ProductVariant variant = request.variantId() == null ? null
                : variantRepository.findById(request.variantId())
                        .orElseThrow(() -> new VariantSelection.VariantNotApplicableException(product.getName()));
        VariantSelection.validate(product, variant,
                variantRepository.findByProductIdOrderByPositionAscIdAsc(product.getId()));

        Cart cart = cartRepository.findWithItemsByUserId(userId)
                .orElseGet(() -> newCartFor(userId));

        // A line is identified by (product, OPTION) now. Matching on product
        // alone would merge Small into the Large line and quietly change what
        // the shopper is buying.
        cart.getItems().stream()
                .filter(ci -> ci.getProduct().getId().equals(product.getId())
                        && sameVariant(ci, variant))
                .findFirst()
                .ifPresentOrElse(
                        existing -> existing.setQuantity(
                                Math.min(999, existing.getQuantity() + request.quantity())),
                        () -> {
                            CartItem item = new CartItem();
                            item.setCart(cart);
                            item.setProduct(product);
                            item.setVariant(variant);
                            item.setQuantity(request.quantity());
                            cart.getItems().add(item);
                        });

        return toResponse(cartRepository.save(cart));
    }

    /** Null-safe identity for "the same line", where null means no option. */
    private static boolean sameVariant(CartItem item, @Nullable ProductVariant variant) {
        Long a = item.getVariant() == null ? null : item.getVariant().getId();
        Long b = variant == null ? null : variant.getId();
        return java.util.Objects.equals(a, b);
    }

    /**
     * variantId identifies WHICH line when a product is in the cart more than
     * once. Null means the line with no option — which is every line for a
     * product that has none, and the only line that existed before V25.
     */
    @Transactional
    public CartResponse updateQuantity(Long userId, Long productId,
                                       @Nullable Long variantId, int quantity) {
        Cart cart = requireCart(userId);
        CartItem item = cart.getItems().stream()
                .filter(ci -> ci.getProduct().getId().equals(productId)
                        && java.util.Objects.equals(variantIdOf(ci), variantId))
                .findFirst()
                .orElseThrow(() -> new ProductNotFoundException(productId));
        item.setQuantity(quantity);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long productId, @Nullable Long variantId) {
        Cart cart = requireCart(userId);
        // orphanRemoval on Cart.cartItems turns this removal into a DELETE.
        // Matching the option too, or removing Small would take Large with it.
        cart.getItems().removeIf(ci -> ci.getProduct().getId().equals(productId)
                && java.util.Objects.equals(variantIdOf(ci), variantId));
        return toResponse(cart);
    }

    private static Long variantIdOf(CartItem item) {
        return item.getVariant() == null ? null : item.getVariant().getId();
    }

    @Transactional
    public void clear(Long userId) {
        cartRepository.findWithItemsByUserId(userId)
                .ifPresent(cart -> cart.getItems().clear());
    }

    private Cart requireCart(Long userId) {
        return cartRepository.findWithItemsByUserId(userId)
                .orElseGet(() -> newCartFor(userId));
    }

    private Cart newCartFor(Long userId) {
        Cart cart = new Cart();
        cart.setUser(userRepository.getReferenceById(userId));
        return cartRepository.save(cart);
    }

    private CartResponse toResponse(Cart cart) {
        // One query for every cover photo in the cart, not one per line.
        // Same batch rule as popularity and variants elsewhere: a cart with
        // twelve items must not become twelve image lookups.
        Map<Long, String> coverUrls = coverUrls(cart);

        List<CartLine> lines = cart.getItems().stream()
                .map(ci -> {
                    Product p = ci.getProduct();
                    ProductVariant v = ci.getVariant();
                    // Price and stock come from whichever side owns them.
                    // Quoting the product's price for a variant line is how a
                    // cart total stops matching what checkout charges.
                    BigDecimal unitPrice = VariantSelection.priceOf(p, v);
                    BigDecimal lineTotal = unitPrice
                            .multiply(BigDecimal.valueOf(ci.getQuantity()));
                    return new CartLine(p.getId(), p.getName(), unitPrice,
                            ci.getQuantity(), lineTotal,
                            VariantSelection.stockOf(p, v),
                            v == null ? null : v.getId(),
                            v == null ? null : v.getLabel(),
                            // The product's FIRST photo (V24), matching what
                            // its card and the product page lead with, so a
                            // cart row shows the same picture the shopper
                            // clicked. Null when the vendor uploaded none.
                            coverUrls.get(p.getId()));
                })
                .toList();
        BigDecimal subtotal = lines.stream()
                .map(CartLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(lines, subtotal);
    }

    /**
     * Product id to its FIRST photo's URL, for everything in this cart.
     *
     * Keeps only the first image per product: the repository returns them
     * ordered by (position, id), so the earliest one encountered per product
     * is the cover, and mergeFunction keeps it rather than the last.
     */
    private Map<Long, String> coverUrls(Cart cart) {
        List<Long> productIds = cart.getItems().stream()
                .map(ci -> ci.getProduct().getId())
                .toList();
        if (productIds.isEmpty()) return Map.of();

        return imageRepository.findByProductIdInOrderByPositionAscIdAsc(productIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        img -> img.getProduct().getId(),
                        img -> storage.publicUrl(img.getImageKey()),
                        (first, later) -> first));
    }
}
