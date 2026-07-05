package com.marketplace.api.controller;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.OrderAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin order operations. Double-gated: the /api/v1/admin/** rule in
 * SecurityConfig requires ROLE_ADMIN before routing, and @PreAuthorize repeats
 * it at the class so the protection survives a SecurityConfig refactor that
 * forgets the URL rule (defense in depth).
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final OrderAdminService orderAdminService;

    public AdminOrderController(OrderAdminService orderAdminService) {
        this.orderAdminService = orderAdminService;
    }

    public record TransitionRequest(
            @NotNull OrderStatus status,
            @Size(max = 500) String note
    ) {}

    /** History entries flattened for the API — no entity graphs over the wire. */
    public record HistoryEntry(
            String fromStatus,
            String toStatus,
            Long changedByUserId,
            String note,
            LocalDateTime at        // LocalDateTime: BaseEntity.getCreatedAt() returns LocalDateTime
    ) {
        static HistoryEntry from(OrderStatusHistory h) {
            return new HistoryEntry(
                    h.getFromStatus() != null ? h.getFromStatus().name() : null,
                    h.getToStatus().name(),
                    h.getChangedBy().getId(),
                    h.getNote(),
                    h.getCreatedAt());
        }
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Void> transition(
            @PathVariable Long id,
            @Valid @RequestBody TransitionRequest request,
            @AuthenticationPrincipal UserPrincipal admin) {
        orderAdminService.transition(id, request.status(), admin.getId(), request.note());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@PathVariable Long id) {
        return orderAdminService.history(id).stream().map(HistoryEntry::from).toList();
    }
}
