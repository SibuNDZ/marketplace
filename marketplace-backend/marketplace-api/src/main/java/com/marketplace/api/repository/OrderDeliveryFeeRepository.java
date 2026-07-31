package com.marketplace.api.repository;

import com.marketplace.api.entity.OrderDeliveryFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderDeliveryFeeRepository extends JpaRepository<OrderDeliveryFee, Long> {

    /** This vendor's fee rows across a page of orders — one query, no lazy walks. */
    List<OrderDeliveryFee> findByOrderIdInAndVendorId(Collection<Long> orderIds, Long vendorId);
}
