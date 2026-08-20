package com.marketplace.api.controller;

import com.marketplace.api.dto.PayoutDtos.ApproveBatchRequest;
import com.marketplace.api.dto.PayoutDtos.BatchSummary;
import com.marketplace.api.dto.PayoutDtos.MarkPaidRequest;
import com.marketplace.api.dto.PayoutDtos.VendorPendingGroup;
import com.marketplace.api.payout.PayoutAdminService;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The payout run, admin-only. Double-gated like AdminOrderController: the
 * /api/v1/admin/** rule in SecurityConfig requires ROLE_ADMIN before routing,
 * and @PreAuthorize repeats it at the class (defense in depth).
 *
 * Thin shell over PayoutAdminService — no business logic here, and no
 * banking data beyond the masked form the DTOs carry. The one exception is
 * the CSV export, which necessarily holds full account numbers and is
 * therefore generated on demand and never stored.
 */
@RestController
@RequestMapping("/api/v1/admin/payouts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPayoutController {

    private final PayoutAdminService payoutAdminService;

    public AdminPayoutController(PayoutAdminService payoutAdminService) {
        this.payoutAdminService = payoutAdminService;
    }

    /** Everything awaiting approval, grouped per vendor with totals. */
    @GetMapping("/pending")
    public List<VendorPendingGroup> pending() {
        return payoutAdminService.pending();
    }

    @PostMapping("/batches")
    public BatchSummary approve(@Valid @RequestBody ApproveBatchRequest request,
                                @AuthenticationPrincipal UserPrincipal admin) {
        return payoutAdminService.approve(request.entryIds(), admin.getId());
    }

    @GetMapping("/batches")
    public List<BatchSummary> batches() {
        return payoutAdminService.batches();
    }

    @GetMapping("/batches/{id}/export")
    public ResponseEntity<String> export(@PathVariable Long id) {
        String csv = payoutAdminService.exportCsv(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"erestyu-payout-batch-" + id + ".csv\"")
                .body(csv);
    }

    @PostMapping("/batches/{id}/paid")
    public BatchSummary markPaid(@PathVariable Long id,
                                 @Valid @RequestBody MarkPaidRequest request,
                                 @AuthenticationPrincipal UserPrincipal admin) {
        return payoutAdminService.markPaid(id, request.paymentReference(), admin.getId());
    }
}
