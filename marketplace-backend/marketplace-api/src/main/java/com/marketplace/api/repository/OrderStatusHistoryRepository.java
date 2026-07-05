package com.marketplace.api.repository;

import com.marketplace.api.entity.OrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);
    // Secondary sort on id breaks ties when two transitions land in the same
    // timestamp tick — createdAt precision alone is not a total order.
}
