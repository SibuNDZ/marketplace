package com.marketplace.api.email;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.service.OrderStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bridges order state transitions to email, with three layers of isolation
 * between "order committed" and "email sent":
 *
 * 1. AFTER_COMMIT: the listener fires only once the transition is durable.
 *    A rolled-back PAID sends nothing — there is no order to confirm.
 * 2. @Async: the Stripe webhook and the ship action return without waiting
 *    on Resend. A slow provider cannot back-pressure payments.
 * 3. Catch-all: the transport already swallows failures, but a bug in
 *    composition itself (an unexpected null, a template error) must also
 *    die here, on this thread, as a log line.
 *
 * The order is reloaded in a short read-only transaction because the
 * publishing transaction's entities are detached by the time this runs; the
 * email graph (buyer, items, vendors) is fully fetched inside it, then read
 * after it closes. The transaction is deliberately closed BEFORE sending so
 * a slow HTTP call never holds a database connection.
 */
@Component
public class OrderEmailListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEmailListener.class);

    private final OrderRepository orderRepository;
    private final OrderEmailService orderEmailService;
    private final TransactionTemplate readOnlyTx;

    public OrderEmailListener(OrderRepository orderRepository,
                              OrderEmailService orderEmailService,
                              PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderEmailService = orderEmailService;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.to() != OrderStatus.PAID && event.to() != OrderStatus.SHIPPED) {
            return;
        }
        try {
            Order order = readOnlyTx.execute(status ->
                    orderRepository.findWithItemsAndVendorsById(event.orderId()).orElse(null));
            if (order == null) {
                log.error("Order {} vanished between {} transition and email send",
                        event.orderId(), event.to());
                return;
            }
            switch (event.to()) {
                case PAID -> orderEmailService.sendOrderPaidEmails(order);
                case SHIPPED -> orderEmailService.sendOrderShippedEmail(order);
                default -> { }
            }
        } catch (Exception e) {
            log.error("Order {} email for {} transition failed — order state is unaffected",
                    event.orderId(), event.to(), e);
        }
    }
}
