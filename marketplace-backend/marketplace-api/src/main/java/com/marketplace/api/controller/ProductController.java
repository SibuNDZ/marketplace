package com.marketplace.api.controller;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ProductService;
import com.marketplace.api.service.ProductStockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

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

    private final ProductService productService;
    private final ProductStockService productStockService;

    public ProductController(ProductService productService,
                             ProductStockService productStockService) {
        this.productService = productService;
        this.productStockService = productStockService;
    }

    @GetMapping
    public Page<ProductResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return productService.list(pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id,
                               @AuthenticationPrincipal UserPrincipal me) {
        return productService.get(id, me != null ? me.getId() : null);
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
}
