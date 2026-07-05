package com.marketplace.api.controller;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return productService.list(pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
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
}
