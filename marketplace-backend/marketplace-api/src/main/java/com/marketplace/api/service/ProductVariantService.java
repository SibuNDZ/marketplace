package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.dto.ProductDtos.VariantRequest;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ProductVariantRepository;
import com.marketplace.api.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor-side management of a product's purchasable options (V20).
 *
 * Ownership is enforced here on every call, not just at the controller's
 * role gate: hasRole('VENDOR') only proves the caller is *a* vendor, never
 * that they own *this* product. That distinction is exactly what the
 * dashboard scoping bug got wrong, so it is checked in the service where it
 * cannot be forgotten by a new endpoint.
 *
 * Nothing here touches the buy path yet. Variants are inert until the cart
 * and order changes land (product-variants.md step 2).
 */
@Service
public class ProductVariantService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductService productService;

    public ProductVariantService(ProductRepository productRepository,
                                 ProductVariantRepository variantRepository,
                                 ProductService productService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.productService = productService;
    }

    @Transactional
    public ProductResponse add(Long productId, VariantRequest request, UserPrincipal me) {
        Product product = ownedProduct(productId, me);
        String label = request.label().strip();
        if (variantRepository.existsByProductIdAndLabelIgnoreCase(productId, label)) {
            throw new DuplicateVariantLabelException(label);
        }

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        apply(variant, request, label);
        variantRepository.save(variant);
        return productService.toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long productId, Long variantId, VariantRequest request, UserPrincipal me) {
        Product product = ownedProduct(productId, me);
        ProductVariant variant = variantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(() -> new VariantNotFoundException(variantId));

        String label = request.label().strip();
        // Renaming onto a sibling's label would break the unique constraint;
        // catching it here gives a usable message instead of a 500.
        if (!variant.getLabel().equalsIgnoreCase(label)
                && variantRepository.existsByProductIdAndLabelIgnoreCase(productId, label)) {
            throw new DuplicateVariantLabelException(label);
        }
        apply(variant, request, label);
        return productService.toResponse(product);
    }

    /**
     * Deleting is allowed even when the variant has sold: order lines keep
     * their own price and label snapshots, exactly like product deletion.
     * History is not rewritten by a catalogue edit.
     */
    @Transactional
    public ProductResponse delete(Long productId, Long variantId, UserPrincipal me) {
        Product product = ownedProduct(productId, me);
        ProductVariant variant = variantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(() -> new VariantNotFoundException(variantId));
        variantRepository.delete(variant);
        return productService.toResponse(product);
    }

    private void apply(ProductVariant variant, VariantRequest request, String label) {
        variant.setLabel(label);
        variant.setSku(request.sku() == null || request.sku().isBlank() ? null : request.sku().strip());
        variant.setPrice(request.price());
        variant.setStockQuantity(request.stock());
        variant.setPosition(request.position() == null ? 0 : request.position());
    }

    /**
     * 404 for someone else's product, not 403: an id the caller does not own
     * should be indistinguishable from one that does not exist, or the
     * endpoint becomes a catalogue-enumeration oracle. ADMIN passes through.
     */
    private Product ownedProduct(Long productId, UserPrincipal me) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        boolean admin = "ADMIN".equals(me.getRole());
        boolean owner = product.getVendor() != null
                && product.getVendor().getId().equals(me.getId());
        if (!admin && !owner) {
            throw new ProductNotFoundException(productId);
        }
        return product;
    }

    public static class DuplicateVariantLabelException extends RuntimeException {
        public DuplicateVariantLabelException(String label) {
            super("This product already has an option called \"" + label + "\"");
        }
    }

    public static class VariantNotFoundException extends RuntimeException {
        public VariantNotFoundException(Long id) {
            super("Variant " + id + " not found");
        }
    }
}
