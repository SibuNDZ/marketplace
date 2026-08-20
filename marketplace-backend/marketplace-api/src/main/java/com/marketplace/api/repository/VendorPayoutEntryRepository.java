package com.marketplace.api.repository;

import com.marketplace.api.entity.VendorPayoutEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorPayoutEntryRepository extends JpaRepository<VendorPayoutEntry, Long> {

    /**
     * The idempotency probe: PRIMARY entries for an order either all exist or
     * none do (they are written in one transaction), so any entry means the
     * order has been recorded.
     */
    boolean existsByOrderId(Long orderId);

    List<VendorPayoutEntry> findByOrderId(Long orderId);
}
