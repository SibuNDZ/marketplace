package com.marketplace.api.controller;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

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

    @GetMapping
    public Page<OrderResponse> myOrders(
            @PageableDefault(size = 20, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal me) {
        return orderService.getMyOrders(me.getId(), pageable);
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
