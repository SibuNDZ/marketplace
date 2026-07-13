package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.discovery.ProductPopularity;
import com.marketplace.api.discovery.ProductPopularityRepository;
import com.marketplace.api.discovery.ProductViewRecorder;
import com.marketplace.api.dto.ProductDtos.CategoryCount;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductCategory;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.ProductExceptions.DuplicateSkuException;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.storage.ObjectStorageService;
import org.springframework.lang.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Product CRUD with the marketplace's core authorization rule: vendors manage
 * ONLY their own products.
 *
 * Two-layer defense:
 *   - Controller: @PreAuthorize("hasAnyRole('VENDOR','ADMIN')") — coarse gate
 *   - Service (here): assertOwnerOrAdmin — fine ownership check
 *
 * Throwing AccessDeniedException means the GlobalExceptionHandler's 403 mapping
 * covers both @PreAuthorize failures and these checks with one handler.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductViewRecorder viewRecorder;
    private final ProductPopularityRepository popularityRepository;
    private final ObjectStorageService storage;

    public ProductService(ProductRepository productRepository,
                          UserRepository userRepository,
                          ProductViewRecorder viewRecorder,
                          ProductPopularityRepository popularityRepository,
                          ObjectStorageService storage) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.viewRecorder = viewRecorder;
        this.popularityRepository = popularityRepository;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Pageable pageable) {
        return toResponses(productRepository.findAllByDeletedAtIsNull(pageable));
    }

    /** ?category= catalog filter. Null category means "all" — same as list(pageable). */
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(@Nullable ProductCategory category, Pageable pageable) {
        if (category == null) return list(pageable);
        return toResponses(productRepository.findAllByCategoryAndDeletedAtIsNull(category, pageable));
    }

    /**
     * Live-product counts per category, for the sidebar. Replaces the
     * frontend's id-arithmetic fabrication with a real grouped count.
     */
    @Transactional(readOnly = true)
    public List<CategoryCount> categoryCounts() {
        return productRepository.countLiveByCategory().stream()
                .map(row -> new CategoryCount((ProductCategory) row[0], (Long) row[1]))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id, @Nullable Long viewerUserId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        // Record happens AFTER orElseThrow: a 404 records nothing, structurally.
        // The call is async (ProductViewRecorder) — it never blocks or fails this request.
        viewRecorder.record(id, viewerUserId);
        return toResponse(product);
    }

    @Transactional
    public ProductResponse create(ProductRequest request, UserPrincipal me) {
        assertSkuAvailable(request.sku());
        Product product = new Product();
        applyRequest(product, request);
        product.setVendor(userRepository.getReferenceById(me.getId()));
        try {
            // saveAndFlush: force the INSERT here so a SKU race surfaces inside
            // the try (house pattern from ReviewService), not at commit.
            return toResponse(productRepository.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSkuException(request.sku());
        }
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request, UserPrincipal me) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        assertOwnerOrAdmin(product, me);
        // Only check when the SKU actually changes — the product's own live row
        // would otherwise fail the exists check against itself.
        if (!request.sku().equals(product.getSku())) {
            assertSkuAvailable(request.sku());
        }
        applyRequest(product, request);
        try {
            return toResponse(productRepository.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSkuException(request.sku());
        }
    }

    @Transactional
    public void delete(Long id, UserPrincipal me) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        assertOwnerOrAdmin(product, me);
        product.setDeletedAt(java.time.LocalDateTime.now());
    }

    /** Pre-check for the clean 409; the saveAndFlush catch is the race backstop. */
    private void assertSkuAvailable(String sku) {
        if (productRepository.existsBySkuAndDeletedAtIsNull(sku)) {
            throw new DuplicateSkuException(sku);
        }
    }

    private void assertOwnerOrAdmin(Product product, UserPrincipal me) {
        boolean isAdmin = "ADMIN".equals(me.getRole());
        boolean isOwner = product.getVendor() != null
                && product.getVendor().getId().equals(me.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException(
                    "Vendor " + me.getId() + " does not own product " + product.getId());
        }
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setSku(request.sku());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(request.category());
    }

    // ---- mapping: ONE enriched mapper, three shapes over it -------------
    // Batch is the required shape for any list (one popularity query per
    // page); the single-product variant does one lookup and exists for
    // get/create/update. Never loop the single variant over a list.

    /** Single product — one popularity lookup. */
    @Transactional(readOnly = true)
    public ProductResponse toResponse(Product p) {
        return toResponse(p, popularityRepository.findById(p.getId()).orElse(null));
    }

    /** Batch — one findAllById covers the whole list. Preserves input order. */
    @Transactional(readOnly = true)
    public List<ProductResponse> toResponses(List<Product> products) {
        Map<Long, ProductPopularity> pop = popularityMap(products);
        return products.stream().map(p -> toResponse(p, pop.get(p.getId()))).toList();
    }

    /** Batch over a page — same single query, pagination metadata preserved. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> toResponses(Page<Product> page) {
        Map<Long, ProductPopularity> pop = popularityMap(page.getContent());
        return page.map(p -> toResponse(p, pop.get(p.getId())));
    }

    private Map<Long, ProductPopularity> popularityMap(List<Product> products) {
        List<Long> ids = products.stream().map(Product::getId).toList();
        return popularityRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductPopularity::getProductId, Function.identity()));
    }

    /**
     * Null popularity is NORMAL, not exceptional — a product created since
     * the last hourly rebuild has no row yet. Zeros are the truthful answer.
     */
    private ProductResponse toResponse(Product p, @Nullable ProductPopularity pop) {
        User vendor = p.getVendor();
        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(), p.getSku(),
                p.getPrice(), p.getStock(),
                vendor != null ? vendor.getId() : null,
                vendor != null ? vendor.getFullName() : null,
                pop != null ? pop.getAvgRating() : BigDecimal.ZERO,
                pop != null ? pop.getReviewCount() : 0L,
                pop != null ? pop.getSalesCount() : 0L,
                p.getCreatedAt(),
                p.getCategory(),
                p.getImageKey() != null ? storage.publicUrl(p.getImageKey()) : null);
    }
}
