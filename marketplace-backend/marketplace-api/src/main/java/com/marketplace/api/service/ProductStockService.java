package com.marketplace.api.service;

import com.marketplace.api.entity.Product;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor inventory adjustment — the delta operation flagged in ProductService
 * comments since the MVP. "Add 50 units" instead of "set stock to X", because
 * absolute-set from a stale form races with checkouts: vendor loads the page
 * showing stock 20, three units sell, vendor saves "stock: 25" — three sales
 * silently un-happen. A delta is commutative with concurrent sales; an
 * absolute write is not.
 *
 * Locking: findByIdForUpdate + no prior load in the same transaction. Per
 * hibernate-locking.md, the stale-cache trap requires the entity to already
 * be in the session when the locking query runs. Here the locking load is the
 * FIRST touch in a fresh transaction, so the returned state is the current
 * row — the "third occurrence warning" site from that memory file, pre-answered.
 * If this method ever grows a pre-lock read of the same product, the refresh
 * discipline must come with it.
 *
 * Negative deltas are allowed (shrinkage, damage write-offs) but cannot take
 * stock below zero — that would un-sell inventory checkout already committed
 * to. The floor check happens under the lock, racing with nothing.
 */
@Service
public class ProductStockService {

    private final ProductRepository productRepository;

    public ProductStockService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Returns the resulting stock level so the vendor UI can display
     * post-adjustment truth without a second request.
     */
    @Transactional
    public int adjustStock(Long productId, int delta, UserPrincipal me) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getDeletedAt() != null) {
            throw new ProductNotFoundException(productId);
        }

        boolean isAdmin = "ADMIN".equals(me.getRole());
        boolean isOwner = product.getVendor() != null
                && product.getVendor().getId().equals(me.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException(
                    "Vendor " + me.getId() + " does not own product " + productId);
        }

        int resulting = product.getStock() + delta;
        if (resulting < 0) {
            throw new InsufficientAdjustmentException(productId, product.getStock(), delta);
        }

        product.setStock(resulting);
        return resulting;
    }

    /** 409 in GlobalExceptionHandler: the request conflicts with current stock. */
    public static class InsufficientAdjustmentException extends RuntimeException {
        public InsufficientAdjustmentException(Long productId, int current, int delta) {
            super("Cannot adjust product " + productId + " stock by " + delta
                    + ": current stock is " + current + " and stock cannot go negative");
        }
    }
}
