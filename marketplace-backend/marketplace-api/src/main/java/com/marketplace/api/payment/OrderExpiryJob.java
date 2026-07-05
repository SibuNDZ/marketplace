package com.marketplace.api.payment;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The answer to "abandoned checkouts hold inventory forever": PENDING
 * orders older than the payment window get cancelled, restoring stock.
 *
 * Timing: cutoff = PAYMENT_WINDOW (30 min) + 5 min grace. The Stripe
 * session stops accepting payment at 30 min, so by the time an order is
 * eligible here, payment is normally impossible — the webhook/job race
 * is confined to webhook DELIVERY delay, and PaymentEventService's row
 * lock + status check handles that residue (worst case: the loud
 * MANUAL REFUND log line).
 *
 * Each order is cancelled in its OWN transaction (via the service call),
 * so one poisoned order can't block the sweep — the loop catches, logs,
 * and continues.
 *
 * Requires @EnableScheduling on the application class. Single-instance
 * assumption: if this app ever scales horizontally, add a scheduler lock
 * (ShedLock) — two instances sweeping concurrently is safe against
 * double-restock ONLY because of the row lock in cancelExpired, but
 * it's wasteful and noisy.
 */
@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);
    private static final int GRACE_MINUTES = 5;

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public OrderExpiryJob(OrderRepository orderRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${app.orders.expiry-sweep-ms:60000}")
    public void expireUnpaidOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(
                StripeCheckoutService.PAYMENT_WINDOW_MINUTES + GRACE_MINUTES);

        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.PENDING, cutoff);

        for (Order order : stale) {
            try {
                orderService.cancelExpired(order.getId());
                log.info("Expired unpaid order {} — stock restored", order.getId());
            } catch (Exception e) {
                // One bad order must not stop the sweep; it'll be retried
                // next cycle and the error is visible for investigation.
                log.error("Failed to expire order {}", order.getId(), e);
            }
        }
    }
}
