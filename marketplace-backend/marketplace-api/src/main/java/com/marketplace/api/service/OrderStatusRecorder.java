package com.marketplace.api.service;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared audit writer for order status transitions. Only OrderService,
 * OrderAdminService, and PaymentEventService may call it; accidental writes
 * from controllers or other layers are an architectural violation.
 *
 * {@code Propagation.MANDATORY} means this method throws if invoked outside an
 * active transaction, guaranteeing every history row commits or rolls back with
 * the status change it describes. An audit trail that can disagree with the
 * data it audits is worse than none.
 */
@Component
public class OrderStatusRecorder {

    private final OrderStatusHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    OrderStatusRecorder(OrderStatusHistoryRepository historyRepository,
                        UserRepository userRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Order order, OrderStatus from, OrderStatus to,
                       Long changedByUserId, String note) {
        OrderStatusHistory entry = new OrderStatusHistory();
        entry.setOrder(order);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setChangedBy(userRepository.getReferenceById(changedByUserId));
        entry.setNote(note);
        historyRepository.save(entry);

        // Published inside the MANDATORY transaction, so AFTER_COMMIT listeners
        // (order emails) fire exactly when the transition they describe is
        // durable, and never for a rolled-back transition.
        eventPublisher.publishEvent(new OrderStatusChangedEvent(order.getId(), from, to));
    }
}
