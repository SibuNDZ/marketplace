package com.marketplace.api.controller;

import com.marketplace.api.discovery.PopularityJob;
import com.marketplace.api.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AdminOrderController.class);

    private final OrderAdminService orderAdminService;
    private final OrderService orderService;
    private final PopularityJob popularityJob;

    public AdminOrderController(OrderAdminService orderAdminService, OrderService orderService,
                                PopularityJob popularityJob) {
        this.orderAdminService = orderAdminService;
        this.orderService = orderService;
        this.popularityJob = popularityJob;
    }

    public record TransitionRequest(
            @NotNull OrderStatus status,
            @Size(max = 500) String note,
            /** Only meaningful with status=SHIPPED; ignored otherwise. */
            @Size(max = 100) String trackingNumber
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
            LocalDateTime createdAt,
            /** Placed while checkout was guarded (V34): not a sale. */
            boolean testOrder
    ) {
        static AdminOrderSummary from(Order o) {
            return new AdminOrderSummary(
                    o.getId(),
                    o.getOrderNumber(),
                    o.getUser().getEmail(),
                    o.getStatus().name(),
                    o.getTotalAmount(),
                    o.getCreatedAt(),
                    o.isTestOrder());
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
        orderAdminService.transition(id, request.status(), admin.getId(),
                request.note(), request.trackingNumber());
        return ResponseEntity.noContent().build();
    }

    /**
     * Required reason: it is written into the order's history, which is the
     * only record of why a paid-looking order became CANCELLED.
     */
    public record VoidRequest(
            @jakarta.validation.constraints.NotBlank(message = "Say why this order is being voided")
            @Size(max = 300) String reason
    ) {}

    /**
     * Voids a test-mode order. Refused (409) unless payments run in test
     * mode, unless the order is PAID, SHIPPED or DELIVERED, and if the vendor
     * has already been paid out for it. See OrderService.voidTestOrder.
     */
    @PostMapping("/{id}/void")
    public OrderService.VoidResult voidTestOrder(
            @PathVariable Long id,
            @Valid @RequestBody VoidRequest request,
            @AuthenticationPrincipal UserPrincipal admin) {
        // The void commits inside voidTestOrder; only then is the popularity
        // read model rebuilt. Public "sold" counts come from that model, which
        // otherwise refreshes hourly, so without this the product would keep
        // claiming a sale that never happened for up to an hour after the
        // admin was told it was fixed. A failed rebuild must not undo or
        // misreport a void that already committed: the hourly run catches up.
        OrderService.VoidResult result = orderService.voidTestOrder(id, admin.getId(), request.reason());
        try {
            popularityJob.rebuild();
        } catch (RuntimeException e) {
            log.warn("Order {} voided, but the popularity rebuild failed; sold counts correct "
                    + "at the next hourly run", id, e);
        }
        return result;
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@PathVariable Long id) {
        return orderAdminService.history(id).stream().map(HistoryEntry::from).toList();
    }
}
