package com.marketplace.api.controller;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.OrderAdminService;
import com.marketplace.api.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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
    private final OrderService orderService;

    public AdminOrderController(OrderAdminService orderAdminService, OrderService orderService) {
        this.orderAdminService = orderAdminService;
        this.orderService = orderService;
    }

    public record TransitionRequest(
            @NotNull OrderStatus status,
            @Size(max = 500) String note
    ) {}

    /**
     * List-view projection: deliberately NO items. Mapping items on a paged
     * list drags the orderItems collection into a paged fetch (Hibernate's
     * in-memory-pagination trap); detail and history endpoints cover the
     * drill-down. createdAt is LocalDateTime to match the Order entity.
     */
    public record AdminOrderSummary(
            Long id,
            String orderNumber,
            String customerEmail,
            String status,
            BigDecimal total,
            LocalDateTime createdAt
    ) {
        static AdminOrderSummary from(Order o) {
            return new AdminOrderSummary(
                    o.getId(),
                    o.getOrderNumber(),
                    o.getUser().getEmail(),
                    o.getStatus().name(),
                    o.getTotalAmount(),
                    o.getCreatedAt());
        }
    }

    /**
     * Single-order detail — items plus the shipping address, masked per
     * OrderService.shippingFor's rule (visible only once PAID or later).
     * Without this, an admin could flip PAID->SHIPPED without ever seeing
     * where the order is going, which defeats the point of collecting an
     * address at all.
     */
    @GetMapping("/{id}")
    public OrderResponse detail(@PathVariable Long id) {
        return orderService.getOrderForAdmin(id);
    }

    @GetMapping
    public Page<AdminOrderSummary> list(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return orderAdminService.list(status, pageable).map(AdminOrderSummary::from);
    }

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
