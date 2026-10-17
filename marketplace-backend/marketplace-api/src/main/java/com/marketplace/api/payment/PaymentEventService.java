package com.marketplace.api.payment;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.payout.CommissionLedgerService;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderStatusRecorder;
import com.marketplace.api.service.OrderTransitions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * The lock alone is not enough: under open-in-view the request keeps one
 * EntityManager, so if the caller loaded the order before calling in (the
 * PayFast ITN does, for its amount check), the lock query hands back that
 * already-managed instance with the status it read BEFORE the lock. The
 * order is therefore refreshed after locking, the same fix OrderService
 * applies to products (see lockAndRefresh there).
 *
 * Idempotency: Stripe retries webhooks and can deliver duplicates. A
 * completed event for an already-PAID order is success, not an error —
 * log and return, so Stripe gets its 200 and stops retrying. Providers that
 * pass a {@link ProviderPayment} also get the difference between a
 * redelivery (same reference) and a SECOND payment for the same order
 * (different reference), which is money to refund.
 */
@Service
public class PaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventService.class);

    /**
     * What the provider says was paid, for providers whose webhook carries it.
     * Checked against the order INSIDE the row lock, so the total it is
     * compared with is the committed one.
     *
     * @param reference   the provider's id for this payment; stored on the
     *                    order at PAID so a different payment arriving later
     *                    for the same order is recognisable as a second charge
     * @param amountCents the amount that settles the order, in cents
     * @param currency    ISO 4217 code as the provider reports it
     */
    public record ProviderPayment(String reference, long amountCents, String currency) {}

    @PersistenceContext
    private EntityManager entityManager;

    private final OrderRepository orderRepository;
    private final OrderStatusRecorder recorder;
    private final CommissionLedgerService ledger;
    private final PaymentHealth paymentHealth;

    public PaymentEventService(OrderRepository orderRepository, OrderStatusRecorder recorder,
                               CommissionLedgerService ledger, PaymentHealth paymentHealth) {
        this.orderRepository = orderRepository;
        this.recorder = recorder;
        this.ledger = ledger;
        this.paymentHealth = paymentHealth;
    }

    /**
     * @param provider display label for the audit trail and logs ("Stripe",
     *                 "PayFast", "Yoco", "Paystack"). Every provider funnels through this
     *                 one method, so the label is the only thing that says
     *                 which one moved the money — it was hardcoded to
     *                 "Stripe" until Yoco made the misattribution three-way.
     */
    @Transactional
    public void handleCheckoutCompleted(Long orderId, String provider) {
        complete(orderId, provider, null);
    }

    /**
     * As {@link #handleCheckoutCompleted(Long, String)}, plus an amount and
     * currency check against the locked order and duplicate-payment
     * detection by reference.
     */
    @Transactional
    public void handleCheckoutCompleted(Long orderId, String provider, ProviderPayment payment) {
        complete(orderId, provider, payment);
    }

    private void complete(Long orderId, String provider, ProviderPayment payment) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            // Metadata pointed at a nonexistent order — misconfiguration or a
            // different environment's webhook. Log loudly, return normally:
            // a 4xx/5xx would make the provider retry forever.
            log.error("{} payment webhook for unknown order {}", provider, orderId);
            return;
        }

        // Re-read under the lock we now hold: see the class comment.
        entityManager.refresh(order);

        OrderStatus current = order.getStatus();

        // The very payment that already settled this order, redelivered
        // (a lost 200, or resent from the provider's dashboard). Checked
        // before any status test: once the order has SHIPPED it is no longer
        // payable, and the redelivery would otherwise raise a false MANUAL
        // REFUND REQUIRED for money that was taken exactly once.
        if (payment != null && payment.reference() != null
                && payment.reference().equals(order.getPaymentReference())) {
            log.info("Redelivery of {} payment {} for order {} (status {}) - already applied, ignoring",
                    provider, payment.reference(), orderId, current);
            return;
        }

        // A test order paid in test mode moved no money, so a second or late
        // charge on it is not a refund to make. Keep the refund queue for
        // real money only.
        boolean testMoneyOnly = order.isTestOrder() && paymentHealth.isTestMode();

        if (current == OrderStatus.PAID) {
            String paidBy = order.getPaymentReference();
            if (payment != null && paidBy != null && !paidBy.equals(payment.reference())) {
                if (testMoneyOnly) {
                    log.warn("Second test-mode payment {} via {} for TEST order {} (paid by {}) "
                            + "- no money moved, nothing to refund", payment.reference(), provider,
                            orderId, paidBy);
                    return;
                }
                // Two different successful payments for one order: the
                // customer opened checkout twice (two tabs, or an EFT that
                // settled after they paid again by card) and completed both.
                log.error("DUPLICATE PAYMENT FOR ORDER {} via {}: payment {} arrived but the order "
                        + "was already paid by {} - MANUAL REFUND REQUIRED",
                        orderId, provider, payment.reference(), paidBy);
                return;
            }
            log.info("Duplicate payment webhook for order {} - already PAID, ignoring", orderId);
            return;
        }

        if (!OrderTransitions.isAllowed(current, OrderStatus.PAID) && testMoneyOnly) {
            log.warn("Test-mode payment via {} for TEST order {} (status {}) - no money moved, "
                    + "nothing to refund", provider, orderId, current);
            return;
        }

        if (!OrderTransitions.isAllowed(current, OrderStatus.PAID)) {
            // The genuinely bad case: money was taken but the order is beyond
            // PENDING — almost certainly CANCELLED by the expiry job in the
            // window between session payment and webhook delivery. Stock was
            // restored and possibly resold; the money must go back. Until
            // automated refunds exist, this log line IS the refund queue —
            // it's the string to alert on in Sentry/monitoring.
            log.error("PAYMENT RECEIVED FOR NON-PAYABLE ORDER {} (status {}) - "
                    + "MANUAL REFUND REQUIRED", orderId, current);
            return;
        }

        if (order.isTestOrder() && !paymentHealth.isTestMode()) {
            // Live money for an order the system treats as a rehearsal (no
            // payout, no vendor email). CheckoutPreparation refuses to start
            // such a payment once checkout is open; this is the backstop, for
            // a checkout opened just before the switch to live keys.
            log.error("LIVE PAYMENT {} via {} FOR TEST ORDER {} - MANUAL REFUND REQUIRED, "
                    + "order NOT transitioned; the customer must place the order again",
                    payment == null ? "(no reference)" : payment.reference(), provider, orderId);
            return;
        }

        if (payment != null && !amountSettles(order, payment)) {
            // Money and order disagree: a human must look before anything ships.
            log.error("PAYMENT AMOUNT MISMATCH for order {} via {}: expected {} ZAR but payment {} "
                    + "settles {} cents {} - MANUAL REVIEW REQUIRED, order NOT transitioned",
                    orderId, provider, order.getTotalAmount(), payment.reference(),
                    payment.amountCents(), payment.currency());
            return;
        }

        order.setStatus(OrderStatus.PAID);
        if (payment != null) {
            order.setPaymentReference(payment.reference());
        }
        recorder.record(order, current, OrderStatus.PAID,
                order.getUser().getId(), "Payment completed (" + provider + ")");
        // Same transaction as the status flip, on purpose: an order is never
        // PAID without its commission ledger entries. If the ledger write
        // fails, the whole transition rolls back and the provider retries.
        // This is the ONLY setStatus(PAID) site in the codebase (admin manual
        // PAID is rejected in OrderAdminService), so this one call covers
        // every provider.
        //
        // Except a test order (placed while checkout was guarded, V34): its
        // payment moved no money, so no vendor is owed anything and there is
        // nothing to write. Skipping here, at the one PAID site, is what keeps
        // it off the payout list the admin approves from.
        if (order.isTestOrder()) {
            log.info("Order {} PENDING -> PAID via {} webhook (TEST order: no payout entry)",
                    orderId, provider);
            return;
        }
        ledger.recordOnPaid(order);
        log.info("Order {} PENDING -> PAID via {} webhook", orderId, provider);
    }

    private static boolean amountSettles(Order order, ProviderPayment payment) {
        return "ZAR".equalsIgnoreCase(payment.currency())
                && payment.amountCents() == Money.toCents(order.getTotalAmount());
    }
}
