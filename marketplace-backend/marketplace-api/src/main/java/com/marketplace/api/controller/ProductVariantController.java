package com.marketplace.api.controller;

import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.dto.ProductDtos.VariantRequest;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ProductVariantService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Vendor management of a product's options (V20).
 *
 * The role gate here only proves the caller is a vendor; per-product
 * ownership is enforced in the service, where a future endpoint cannot skip
 * it. Every mutation returns the whole ProductResponse so the client's
 * variant list, summed stock and "from" price stay consistent without a
 * follow-up fetch.
 *
 * Reads are deliberately absent: variants already ride on GET /products/{id}.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants")
@PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
public class ProductVariantController {

    private final ProductVariantService variantService;

    public ProductVariantController(ProductVariantService variantService) {
        this.variantService = variantService;
    }

    @PostMapping
    public ProductResponse add(@PathVariable Long productId,
                               @Valid @RequestBody VariantRequest request,
                               @AuthenticationPrincipal UserPrincipal me) {
        return variantService.add(productId, request, me);
    }

    @PutMapping("/{variantId}")
    public ProductResponse update(@PathVariable Long productId,
                                  @PathVariable Long variantId,
                                  @Valid @RequestBody VariantRequest request,
                                  @AuthenticationPrincipal UserPrincipal me) {
        return variantService.update(productId, variantId, request, me);
    }

    @DeleteMapping("/{variantId}")
    public ProductResponse delete(@PathVariable Long productId,
                                  @PathVariable Long variantId,
                                  @AuthenticationPrincipal UserPrincipal me) {
        return variantService.delete(productId, variantId, me);
    }
}
