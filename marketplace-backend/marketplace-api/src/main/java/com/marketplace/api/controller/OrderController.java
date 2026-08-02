package com.marketplace.api.controller;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.OrderTab;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * POST /api/v1/orders takes NO body: the order is defined entirely by the
 * caller's cart. Identity comes from the token — a customer cannot place or
 * read orders as someone else.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@AuthenticationPrincipal UserPrincipal me) {
        OrderResponse order = orderService.placeOrder(me.getId());
        return ResponseEntity
                .created(URI.create("/api/v1/orders/" + order.id()))
                .body(order);
    }

    /**
     * ?tab= filters by the buyer-facing grouping (see OrderTab), not by raw
     * status: one tab covers two statuses, and the enum keeps that mapping
     * server-side instead of duplicating it in the UI. Absent means ALL.
     */
    @GetMapping
    public Page<OrderResponse> myOrders(
            @RequestParam(required = false) OrderTab tab,
            @PageableDefault(size = 20, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal me) {
        return orderService.getMyOrders(me.getId(), tab, pageable);
    }

    /** Counts for the tab badges, in one request. */
    @GetMapping("/counts")
    public Map<OrderTab, Long> myOrderCounts(@AuthenticationPrincipal UserPrincipal me) {
        return orderService.getMyOrderCounts(me.getId());
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal me) {
        return orderService.getOrder(id, me.getId());
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancelOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal me) {
        return orderService.cancelOrder(id, me.getId());
    }
}
