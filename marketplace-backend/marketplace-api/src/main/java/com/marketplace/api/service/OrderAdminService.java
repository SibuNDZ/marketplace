package com.marketplace.api.service;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.payout.CommissionLedgerService;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final CommissionLedgerService ledger;

    public OrderAdminService(OrderRepository orderRepository,
                             OrderStatusRecorder recorder,
                             OrderStatusHistoryRepository historyRepository,
                             CommissionLedgerService ledger) {
        this.orderRepository = orderRepository;
        this.recorder = recorder;
        this.historyRepository = historyRepository;
        this.ledger = ledger;
    }

    @Transactional
    public void transition(Long orderId, OrderStatus target, Long adminUserId, String note) {
        transition(orderId, target, adminUserId, note, null);
    }

    /**
     * Tracking number is meaningful only when target is SHIPPED (V17, manual
     * interim until a courier API integration); it is ignored otherwise
     * rather than rejected, so a client resending a stale field cannot fail
     * an unrelated transition.
     */
    @Transactional
    public void transition(Long orderId, OrderStatus target, Long adminUserId,
                           String note, String trackingNumber) {
        if (target == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Cancellation must go through the cancel endpoint (it restores stock)");
        }
        if (target == OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Orders cannot transition back to PENDING");
        }
        if (target == OrderStatus.PAID) {
            throw new InvalidOrderStateException(
                    "PAID is set by the payment webhook, not manually");
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

        if (target == OrderStatus.SHIPPED
                && trackingNumber != null && !trackingNumber.isBlank()) {
            order.setTrackingNumber(trackingNumber.strip());
        }
        order.setStatus(target);
        recorder.record(order, current, target, adminUserId, note);

        // REFUNDED is DELIVERED-only in the transition map and this generic
        // path is its sole route, so the ledger reversal lives here. This is
        // the first side effect REFUNDED has grown — per the class comment,
        // if it gains more (gateway refund call, restocking), it graduates
        // into its own method the way cancelOrder did.
        if (target == OrderStatus.REFUNDED) {
            ledger.reverseOnRefund(order);
        }
    }

    /**
     * Paged list for the admin console. Returns entities with user pre-fetched
     * (repository EntityGraph); the controller maps to a summary DTO that never
     * touches orderItems — item detail belongs to findWithItemsById + history.
     */
    @Transactional(readOnly = true)
    public Page<Order> list(OrderStatus status, Pageable pageable) {
        return status == null
                ? orderRepository.findBy(pageable)
                : orderRepository.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistory> history(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
    }
}
