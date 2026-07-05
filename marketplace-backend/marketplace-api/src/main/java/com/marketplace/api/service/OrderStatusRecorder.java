package com.marketplace.api.service;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.entity.OrderStatusHistory;
import com.marketplace.api.repository.OrderStatusHistoryRepository;
import com.marketplace.api.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared audit writer for order status transitions. Package-private: only
 * OrderService and OrderAdminService may call it, preventing accidental writes
 * from other packages.
 *
 * {@code Propagation.MANDATORY} means this method throws if invoked outside an
 * active transaction, guaranteeing every history row commits or rolls back with
 * the status change it describes. An audit trail that can disagree with the
 * data it audits is worse than none.
 */
@Component
class OrderStatusRecorder {

    private final OrderStatusHistoryRepository historyRepository;
    private final UserRepository userRepository;

    OrderStatusRecorder(OrderStatusHistoryRepository historyRepository,
                        UserRepository userRepository) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
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
    }
}
