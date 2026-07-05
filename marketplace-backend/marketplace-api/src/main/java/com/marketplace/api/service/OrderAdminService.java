package com.marketplace.api.service;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin-driven order status transitions.
 *
 * CANCELLED is deliberately rejected here even though the state machine allows
 * PENDING -> CANCELLED, because cancellation has stock-restoration side effects
 * that live in {@link OrderService#cancelOrder}. Routing an admin "cancel"
 * through this method would skip stock restoration and silently corrupt
 * inventory — directing the caller to the right endpoint beats that.
 *
 * When REFUNDED gains real behavior (Stripe call, restocking policy), it
 * graduates from the generic transition into its own method, exactly as
 * cancelOrder did. This generic path is only correct while transitions are
 * pure status changes with no side effects.
 */
@Service
public class OrderAdminService {

    private final OrderRepository orderRepository;
    private final OrderStatusRecorder recorder;
    private final OrderStatusHistoryRepository historyRepository;

    public OrderAdminService(OrderRepository orderRepository,
                             OrderStatusRecorder recorder,
                             OrderStatusHistoryRepository historyRepository) {
        this.orderRepository = orderRepository;
        this.recorder = recorder;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public void transition(Long orderId, OrderStatus target, Long adminUserId, String note) {
        if (target == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Cancellation must go through the cancel endpoint (it restores stock)");
        }
        if (target == OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Orders cannot transition back to PENDING");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus current = order.getStatus();
        if (!OrderTransitions.isAllowed(current, target)) {
            throw new InvalidOrderStateException(
                    "Illegal transition " + current + " -> " + target
                    + " for order " + orderId
                    + "; allowed from " + current + ": " + OrderTransitions.allowedFrom(current));
        }

        order.setStatus(target);
        recorder.record(order, current, target, adminUserId, note);
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistory> history(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
    }
}
