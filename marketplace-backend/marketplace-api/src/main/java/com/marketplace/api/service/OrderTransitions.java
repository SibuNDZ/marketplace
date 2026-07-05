package com.marketplace.api.service;

import com.marketplace.api.entity.OrderStatus;

import java.util.Map;
import java.util.Set;

/**
 * The order state machine, as data. One place defines what is legal; every
 * caller (admin transitions, customer cancellation, future refund flow) asks
 * this class instead of encoding its own if-chains.
 *
 *   PENDING ──▶ SHIPPED ──▶ DELIVERED ──▶ REFUNDED
 *      │
 *      └──▶ CANCELLED
 *
 * Deliberate exclusions (absence reads as decision, not oversight):
 * - SHIPPED -> CANCELLED: once goods move, the path is deliver-then-refund,
 *   not cancel. Add it later if ops genuinely intercepts shipments.
 * - REFUNDED from PENDING/SHIPPED: refund implies money moved on a completed
 *   order; pre-delivery aborts are cancellations.
 * - Anything out of CANCELLED/REFUNDED: terminal means terminal.
 * - CONFIRMED/PROCESSING are in the DB constraint but not wired yet;
 *   getOrDefault returns Set.of(), so any transition from them is rejected.
 *
 * WHO may perform a transition is intentionally not encoded here — that is
 * authorization (@PreAuthorize + service ownership checks), not state legality.
 * The same map serves the admin endpoint and the customer cancel path.
 */
public final class OrderTransitions {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            OrderStatus.PENDING,   Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED,   Set.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, Set.of(OrderStatus.REFUNDED),
            OrderStatus.CANCELLED, Set.of(),
            OrderStatus.REFUNDED,  Set.of()
    );

    public static boolean isAllowed(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<OrderStatus> allowedFrom(OrderStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    private OrderTransitions() {}
}
