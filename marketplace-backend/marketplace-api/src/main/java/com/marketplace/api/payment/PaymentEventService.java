package com.marketplace.api.payment;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderStatusRecorder;
import com.marketplace.api.service.OrderTransitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The webhook's business core, separated from HTTP + signature verification
 * so it can be integration-tested without forging Stripe signatures. The
 * controller is a thin shell; THIS is where the tests aim.
 *
 * Concurrency: the webhook and the expiry job race on the same PENDING
 * order — webhook wants PAID, job wants CANCELLED. Both therefore load the
 * order with findByIdForUpdate (SELECT ... FOR UPDATE): whoever locks first
 * wins, the loser sees the committed status and backs off cleanly. Without
 * the lock, the job could overwrite a just-committed PAID with CANCELLED
 * and restock sold goods — read-check-write on status is exactly the same
 * race as stock was.
 *
 * Idempotency: Stripe retries webhooks and can deliver duplicates. A
 * completed event for an already-PAID order is success, not an error —
 * log and return, so Stripe gets its 200 and stops retrying.
 */
@Service
public class PaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusRecorder recorder;

    public PaymentEventService(OrderRepository orderRepository, OrderStatusRecorder recorder) {
        this.orderRepository = orderRepository;
        this.recorder = recorder;
    }

    @Transactional
    public void handleCheckoutCompleted(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            // Metadata pointed at a nonexistent order — misconfiguration or a
            // different environment's webhook. Log loudly, return normally:
            // a 4xx/5xx would make Stripe retry forever.
            log.error("Stripe checkout.session.completed for unknown order {}", orderId);
            return;
        }

        OrderStatus current = order.getStatus();

        if (current == OrderStatus.PAID) {
            log.info("Duplicate payment webhook for order {} — already PAID, ignoring", orderId);
            return;
        }

        if (!OrderTransitions.isAllowed(current, OrderStatus.PAID)) {
            // The genuinely bad case: money was taken but the order is beyond
            // PENDING — almost certainly CANCELLED by the expiry job in the
            // window between session payment and webhook delivery. Stock was
            // restored and possibly resold; the money must go back. Until
            // automated refunds exist, this log line IS the refund queue —
            // it's the string to alert on in Sentry/monitoring.
            log.error("PAYMENT RECEIVED FOR NON-PAYABLE ORDER {} (status {}) — "
                    + "MANUAL REFUND REQUIRED", orderId, current);
            return;
        }

        order.setStatus(OrderStatus.PAID);
        recorder.record(order, current, OrderStatus.PAID,
                order.getUser().getId(), "Payment completed (Stripe)");
        log.info("Order {} PENDING -> PAID via Stripe webhook", orderId);
    }
}
