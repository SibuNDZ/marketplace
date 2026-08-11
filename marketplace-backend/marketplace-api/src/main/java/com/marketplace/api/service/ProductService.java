package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.discovery.ProductPopularity;
import com.marketplace.api.discovery.ProductPopularityRepository;
import com.marketplace.api.discovery.ProductViewRecorder;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import com.marketplace.api.entity.Category;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.ProductExceptions.DuplicateSkuException;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ProductVariantRepository;
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
import java.util.Objects;
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
    private final CategoryService categoryService;
    private final ProductVariantRepository variantRepository;

    public ProductService(ProductRepository productRepository,
                          UserRepository userRepository,
                          ProductViewRecorder viewRecorder,
                          ProductPopularityRepository popularityRepository,
                          ObjectStorageService storage,
                          CategoryService categoryService,
                          ProductVariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.viewRecorder = viewRecorder;
        this.popularityRepository = popularityRepository;
        this.storage = storage;
        this.categoryService = categoryService;
        this.variantRepository = variantRepository;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Pageable pageable) {
        return toResponses(productRepository.findAllByDeletedAtIsNull(pageable));
    }

    /**
     * The vendor dashboard's listing: ONLY the caller's products, and unlike
     * every catalog query it INCLUDES soft-deleted rows — the dashboard's
     * Archived tab is exactly those. Scoping by the token's user id is the
     * fix for the dashboard showing the whole marketplace to every vendor.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> listMine(Long vendorId, Pageable pageable) {
        return toResponses(productRepository.findByVendorId(vendorId, pageable));
    }

    /**
     * ?category= and ?handmade= catalogue filters. Both null means "all".
     *
     * A top-level slug matches the root AND its children (CategoryService
     * .resolveToIds): browsing Fashion has to show the jewellery, not an
     * empty page because everything is filed one level down. That single
     * behaviour is why this takes a slug and expands it here rather than
     * matching one id.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(@Nullable String categorySlug,
                                      @Nullable Boolean handmade,
                                      Pageable pageable) {
        return list(categorySlug, handmade, null, null, pageable);
        }

        @Transactional(readOnly = true)
        public Page<ProductResponse> list(@Nullable String categorySlug,
                          @Nullable Boolean handmade,
                          @Nullable String name,
                          Pageable pageable) {
        return list(categorySlug, handmade, name, null, pageable);
        }

    /**
     * vendorId is the public storefront filter: LIVE products only, so it
     * cannot be used to enumerate a vendor's archived listings.
     */
        @Transactional(readOnly = true)
        public Page<ProductResponse> list(@Nullable String categorySlug,
                          @Nullable Boolean handmade,
                          @Nullable String name,
                          @Nullable Long vendorId,
                          Pageable pageable) {
        List<Long> categoryIds = categorySlug == null || categorySlug.isBlank()
                ? null
                : categoryService.resolveToIds(categorySlug);
        boolean searchDisabled = name == null || name.isBlank();
        String searchText = searchDisabled
            ? ""
            : name.strip();

        if (categoryIds == null && handmade == null && searchDisabled && vendorId == null) return list(pageable);

        return toResponses(
            productRepository.findFiltered(categoryIds, handmade, searchDisabled, searchText, vendorId, pageable));
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
        product.setCategory(categoryService.requireBySlug(request.categorySlug()));
        product.setHandmade(request.handmadeOrFalse());
        product.setTags(normaliseTags(request.tagsOrEmpty()));
    }

    /**
     * Lowercased, trimmed, deduplicated, blanks dropped, order preserved.
     *
     * Without this "Vegan", "vegan ", and "vegan" are three different tags
     * and the filter silently splits a vendor's own catalogue across them.
     * Normalising on write rather than on read means the GIN index matches
     * exactly what a filter chip sends.
     */
    private static List<String> normaliseTags(List<String> raw) {
        return raw.stream()
                .filter(Objects::nonNull)
                .map(t -> t.trim().toLowerCase())
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    // ---- mapping: ONE enriched mapper, three shapes over it -------------
    // Batch is the required shape for any list (one popularity query per
    // page); the single-product variant does one lookup and exists for
    // get/create/update. Never loop the single variant over a list.

    /** Single product — one popularity lookup. */
    @Transactional(readOnly = true)
    public ProductResponse toResponse(Product p) {
        return toResponse(p, popularityRepository.findById(p.getId()).orElse(null),
                variantRepository.findByProductIdOrderByPositionAscIdAsc(p.getId()));
    }

    /** Batch — one findAllById covers the whole list. Preserves input order. */
    @Transactional(readOnly = true)
    public List<ProductResponse> toResponses(List<Product> products) {
        Map<Long, ProductPopularity> pop = popularityMap(products);
        Map<Long, List<ProductVariant>> variants = variantMap(products);
        return products.stream()
                .map(p -> toResponse(p, pop.get(p.getId()),
                        variants.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** Batch over a page — same single query, pagination metadata preserved. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> toResponses(Page<Product> page) {
        Map<Long, ProductPopularity> pop = popularityMap(page.getContent());
        Map<Long, List<ProductVariant>> variants = variantMap(page.getContent());
        return page.map(p -> toResponse(p, pop.get(p.getId()),
                variants.getOrDefault(p.getId(), List.of())));
    }

    /** One query for a whole page of products, so a grid is not N+1. */
    private Map<Long, List<ProductVariant>> variantMap(List<Product> products) {
        List<Long> ids = products.stream().map(Product::getId).toList();
        if (ids.isEmpty()) return Map.of();
        return variantRepository.findByProductIdInOrderByPositionAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
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
    private ProductResponse toResponse(Product p, @Nullable ProductPopularity pop,
                                       List<ProductVariant> variants) {
        User vendor = p.getVendor();
        Category category = p.getCategory();

        // A product with variants delegates BOTH stock and price to them
        // (V20). Stock is the sum, so "in stock" means "some option is
        // buyable"; price is the minimum, so a card reads as "from R120"
        // rather than quoting an option the shopper might not pick. Neither
        // is stored — a maintained total would be a dual write, and dual
        // writes drift.
        boolean hasVariants = !variants.isEmpty();
        int effectiveStock = hasVariants
                ? variants.stream().mapToInt(ProductVariant::getStockQuantity).sum()
                : p.getStock();
        BigDecimal effectivePrice = hasVariants
                ? variants.stream().map(ProductVariant::getPrice)
                        .min(BigDecimal::compareTo).orElse(p.getPrice())
                : p.getPrice();

        List<ProductDtos.VariantResponse> variantResponses = variants.stream()
                .map(v -> new ProductDtos.VariantResponse(
                        v.getId(), v.getLabel(), v.getSku(), v.getPrice(),
                        v.getStockQuantity(),
                        v.getImageKey() != null ? storage.publicUrl(v.getImageKey()) : null))
                .toList();

        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(), p.getSku(),
                effectivePrice, effectiveStock,
                vendor != null ? vendor.getId() : null,
                // Storefront name, NOT the person's name: a listing is
                // attributed to the business that sells it (V19).
                vendor != null ? vendor.getStorefrontName() : null,
                pop != null ? pop.getAvgRating() : BigDecimal.ZERO,
                pop != null ? pop.getReviewCount() : 0L,
                pop != null ? pop.getSalesCount() : 0L,
                p.getCreatedAt(),
                category.getSlug(),
                category.getName(),
                category.isTopLevel() ? null : category.getParent().getSlug(),
                Boolean.TRUE.equals(p.getHandmade()),
                List.copyOf(p.getTags()),
                p.getImageKey() != null ? storage.publicUrl(p.getImageKey()) : null,
                p.getDeletedAt(),
                variantResponses);
    }
}
