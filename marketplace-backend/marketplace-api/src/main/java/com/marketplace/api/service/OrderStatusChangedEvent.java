package com.marketplace.api.service;

import com.marketplace.api.entity.OrderStatus;

/**
 * Published by {@link OrderStatusRecorder} for every audited transition, INSIDE
 * the transaction that performs it. Consumers that must not act on rolled-back
 * transitions (email, webhooks) listen with AFTER_COMMIT semantics; the event
 * deliberately carries only the order id, because by the time an async
 * after-commit listener runs, entities from the originating transaction are
 * detached and their lazy associations unloadable — the listener reloads.
 */
public record OrderStatusChangedEvent(Long orderId, OrderStatus from, OrderStatus to) {}
