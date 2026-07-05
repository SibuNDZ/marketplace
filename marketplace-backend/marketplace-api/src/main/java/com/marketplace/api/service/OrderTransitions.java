package com.marketplace.api.service;

import com.marketplace.api.entity.OrderStatus;

import java.util.Map;
import java.util.Set;

/**
 * The order state machine, v2 — PAID inserted between PENDING and SHIPPED.
 *
 *   PENDING ──▶ PAID ──▶ SHIPPED ──▶ DELIVERED ──▶ REFUNDED
 *      │
 *      └──▶ CANCELLED        (customer cancel, or payment-window expiry)
 *
 * Changes from v1 and why:
 * - PENDING -> SHIPPED is REMOVED. With payments live, shipping an unpaid
 *   order is a money-losing bug, and the map is where that rule belongs —
 *   not in admin training. (This also means the state-machine test's legal
 *   path changes: PENDING -> PAID -> SHIPPED -> DELIVERED.)
 * - PENDING -> PAID is driven by the Stripe webhook, never by the admin
 *   endpoint (enforced in OrderAdminService, same pattern as CANCELLED).
 * - PAID -> CANCELLED is deliberately ABSENT for now: cancelling a paid
 *   order means moving money back, and there is no refund implementation
 *   yet. When Stripe refunds land, this becomes PAID -> CANCELLED via a
 *   dedicated refundAndCancel method — until then, a paid order's only
 *   path is forward, and support handles exceptions manually.
 */
public final class OrderTransitions {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            OrderStatus.PENDING,   Set.of(OrderStatus.PAID, OrderStatus.CANCELLED),
            OrderStatus.PAID,      Set.of(OrderStatus.SHIPPED),
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
