package com.marketplace.api.service;

import com.marketplace.api.entity.OrderStatus;

import java.util.Set;

/**
 * The buyer-facing groupings of order status.
 *
 * Buyers do not think in the state machine's vocabulary. They ask "where is
 * my stuff", "what have I not paid for", "what did I send back". So the tabs
 * are their own named concept mapping onto statuses, rather than the raw
 * enum leaking into the UI — and one tab deliberately covers two statuses.
 *
 * PROCESSING is PAID only: once a vendor marks an order shipped it moves to
 * SHIPPED, which buyers track differently ("is it on the way" vs "is anyone
 * doing anything"). Merging them would hide the moment that matters most.
 */
public enum OrderTab {

    ALL(Set.of(OrderStatus.values())),
    UNPAID(Set.of(OrderStatus.PENDING)),
    PROCESSING(Set.of(OrderStatus.PAID)),
    SHIPPED(Set.of(OrderStatus.SHIPPED)),
    DELIVERED(Set.of(OrderStatus.DELIVERED)),
    /** One tab, two statuses: a buyer does not distinguish these. */
    RETURNS(Set.of(OrderStatus.CANCELLED, OrderStatus.REFUNDED));

    private final Set<OrderStatus> statuses;

    OrderTab(Set<OrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<OrderStatus> statuses() {
        return statuses;
    }

    public boolean isAll() {
        return this == ALL;
    }
}
