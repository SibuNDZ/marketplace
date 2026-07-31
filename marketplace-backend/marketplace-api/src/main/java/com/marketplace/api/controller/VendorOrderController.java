package com.marketplace.api.controller;

import com.marketplace.api.dto.VendorOrderDtos.VendorOrderResponse;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.VendorOrderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Vendor-facing order endpoints. Identity comes from the token: every query
 * is scoped to the caller's vendor id, so a vendor cannot enumerate or act
 * on another vendor's orders (ownership misses surface as 404 in the
 * service). VENDOR-only, no ADMIN: admins have their own console with the
 * full, unscoped view, and routing them through a vendor-scoped one would
 * only show them an empty list.
 */
@RestController
@RequestMapping("/api/v1/vendor/orders")
@PreAuthorize("hasRole('VENDOR')")
public class VendorOrderController {

    private final VendorOrderService vendorOrderService;

    public VendorOrderController(VendorOrderService vendorOrderService) {
        this.vendorOrderService = vendorOrderService;
    }

    @GetMapping
    public Page<VendorOrderResponse> myOrders(
            @PageableDefault(size = 20, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal me) {
        return vendorOrderService.list(me.getId(), pageable);
    }

    @GetMapping("/{id}")
    public VendorOrderResponse order(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal me) {
        return vendorOrderService.get(me.getId(), id);
    }

    @PostMapping("/{id}/ship")
    public VendorOrderResponse ship(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal me) {
        return vendorOrderService.markShipped(me.getId(), id);
    }
}
