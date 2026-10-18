package com.marketplace.api.controller;

import com.marketplace.api.dto.VendorVerificationDtos.ApproveRequest;
import com.marketplace.api.dto.VendorVerificationDtos.DecisionResponse;
import com.marketplace.api.dto.VendorVerificationDtos.PendingVendor;
import com.marketplace.api.dto.VendorVerificationDtos.RejectRequest;
import com.marketplace.api.payout.VendorVerificationAdminService;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Vendor verification review, admin-only. Shares the /admin/vendors base
 * path with vendor.AdminVendorController (store moderation: social links);
 * the mappings do not overlap. Double-gated like AdminPayoutController: the /api/v1/admin/** rule in SecurityConfig
 * requires ROLE_ADMIN before routing, and @PreAuthorize repeats it at the
 * class (defense in depth).
 *
 * Thin shell over VendorVerificationAdminService. No response here carries
 * a full account number or a full document number: the DTOs have no field
 * for either.
 */
@RestController
@RequestMapping("/api/v1/admin/vendors")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVendorVerificationController {

    private final VendorVerificationAdminService service;

    public AdminVendorVerificationController(VendorVerificationAdminService service) {
        this.service = service;
    }

    /** Vendors whose latest verification row is PENDING, with their recent listings. */
    @GetMapping("/pending")
    public List<PendingVendor> pending() {
        return service.pending();
    }

    /** Note optional; a body may be omitted entirely. 409 on MISMATCH or UNAVAILABLE. */
    @PostMapping("/{vendorId}/approve")
    public DecisionResponse approve(@PathVariable Long vendorId,
                                    @RequestBody(required = false) @Valid ApproveRequest request,
                                    @AuthenticationPrincipal UserPrincipal admin) {
        return service.approve(vendorId, admin.getId(), request == null ? null : request.note());
    }

    /** Note required. Leaves vendor_approved_at alone. */
    @PostMapping("/{vendorId}/reject")
    public DecisionResponse reject(@PathVariable Long vendorId,
                                   @Valid @RequestBody RejectRequest request,
                                   @AuthenticationPrincipal UserPrincipal admin) {
        return service.reject(vendorId, admin.getId(), request.note());
    }
}
