package com.marketplace.api.controller;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ProductService;
import com.marketplace.api.service.ProductStockService;
import com.marketplace.api.storage.ProductImageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * GETs are public (SecurityConfig permits them). Writes: @PreAuthorize gates
 * "is a vendor/admin at all"; the service's ownership check gates "is THIS
 * product's vendor". Both layers must pass.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    // POST /api/v1/products/{id}/stock request and response.
    // POST (not PATCH): a delta is a commutative operation submission — retrying
    // a PATCH is safe, retrying a delta is NOT (double-submit doubles the delta).
    public record StockAdjustmentRequest(
            @NotNull @Min(-10000) @Max(10000) Integer delta) {}
    public record StockAdjustmentResponse(Long productId, int stock) {}

    /** Trailing-24h buyer count, or null when below the display threshold. */
    public record DemandResponse(Long recentBuyers) {}

    private final ProductService productService;
    private final ProductStockService productStockService;
    private final ProductImageService productImageService;
    private final com.marketplace.api.discovery.ProductDemandService demandService;

    public ProductController(ProductService productService,
                             ProductStockService productStockService,
                             ProductImageService productImageService,
                             com.marketplace.api.discovery.ProductDemandService demandService) {
        this.productService = productService;
        this.productStockService = productStockService;
        this.productImageService = productImageService;
        this.demandService = demandService;
    }

    /**
     * category is a SLUG now, not an enum name — ?category=jewellery. A
     * top-level slug also matches its subcategories, so ?category=fashion
     * returns the jewellery too.
     *
     * handmade is a separate axis on purpose: ?handmade=true crosses every
     * category, and combining the two (?category=fashion&handmade=true)
     * is the case that a handmade-as-a-category taxonomy could not express
     * at all.
     */
    @GetMapping
    public Page<ProductResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean handmade,
            @RequestParam(required = false) String name,
            // Public storefront filter: one vendor's LIVE listings, the link
            // a market trader shares on WhatsApp. Live-only by construction
            // (see ProductRepository.findFiltered) so it can never enumerate
            // someone's archived products.
            @RequestParam(required = false) Long vendorId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return productService.list(category, handmade, name, vendorId, pageable);
    }

    /**
     * The vendor dashboard's own-products listing: scoped to the caller,
     * includes archived (soft-deleted) items. Declared before /{id} in
     * reading order but unambiguous to route: "mine" is a literal segment.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
    public Page<ProductResponse> mine(
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @AuthenticationPrincipal UserPrincipal me) {
        return productService.listMine(me.getId(), pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id,
                               @AuthenticationPrincipal UserPrincipal me) {
        return productService.get(id, me != null ? me.getId() : null);
    }

    /**
     * Related products, derived from the catalogue's own text rather than
     * from behaviour there is none of yet. Public: it is the same visibility
     * as the product page it sits on. Returns an empty list when nothing is
     * genuinely related, which the frontend renders as no shelf at all.
     */
    /**
     * Trailing-24h buyer count for the product page's urgency line.
     *
     * A separate call rather than a field on ProductResponse: this is a live
     * count that only one page wants, and putting it on the DTO would run it
     * for every card in every grid. The product page already makes a second
     * call of exactly this shape for its live review summary.
     *
     * recentBuyers is null far more often than not — see ProductDemandService
     * for why silence beats a badge that says "1 person".
     */
    @GetMapping("/{id}/demand")
    public DemandResponse demand(@PathVariable Long id) {
        return new DemandResponse(demandService.recentBuyers(id));
    }

    @GetMapping("/{id}/similar")
    public List<ProductResponse> similar(@PathVariable Long id,
                                         @RequestParam(defaultValue = "6") int limit) {
        return productService.similar(id, Math.clamp(limit, 1, 24));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        ProductResponse created = productService.create(request, me);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
    public ProductResponse update(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return productService.update(id, request, me);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal me) {
        productService.delete(id, me);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
    public StockAdjustmentResponse adjustStock(
            @PathVariable Long id,
            @Valid @RequestBody StockAdjustmentRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return new StockAdjustmentResponse(id,
                productStockService.adjustStock(id, request.delta(), me));
    }

    /**
     * APPENDS a photo to the product's gallery (V24). One file per call: the
     * vendor form uploads sequentially so a single rejected file fails on its
     * own rather than taking a whole batch down with it.
     *
     * Path stays /image singular for the existing frontend upload helper.
     * The behaviour changed underneath it — this used to replace the one
     * photo a product could have.
     */
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('VENDOR','ADMIN')")
    public Map<String, String> uploadImage(@PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal me) {
        return Map.of("imageUrl", productImageService.upload(id, file, me));
    }

    /**
     * Removes one photo and closes the gap in the ordering. Ownership is
     * checked in the service, same as every other product write.
     */
    @DeleteMapping("/{id}/images/{imageId}")
    @PreAuthorize("hasAnyRole('VENDOR','ADMIN')")
    public ResponseEntity<Void> deleteImage(@PathVariable Long id,
            @PathVariable Long imageId,
            @AuthenticationPrincipal UserPrincipal me) {
        productImageService.delete(id, imageId, me);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
