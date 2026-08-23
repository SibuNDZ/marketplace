package com.marketplace.api.repository;

import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.VendorPayoutEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface VendorPayoutEntryRepository extends JpaRepository<VendorPayoutEntry, Long> {

    /**
     * The idempotency probe: PRIMARY entries for an order either all exist or
     * none do (they are written in one transaction), so any entry means the
     * order has been recorded.
     */
    boolean existsByOrderId(Long orderId);

    List<VendorPayoutEntry> findByOrderId(Long orderId);

    /**
     * The payable work list: everything awaiting approval, vendor-fetched (the
     * admin view renders vendor names) and ordered so grouping is stable.
     */
    @Query("""
            SELECT e FROM VendorPayoutEntry e JOIN FETCH e.vendor JOIN FETCH e.order
            WHERE e.status IN :statuses
            ORDER BY e.vendor.id ASC, e.id ASC
            """)
    List<VendorPayoutEntry> findPayable(@Param("statuses") Collection<PayoutEntryStatus> statuses);

    /**
     * Locked load for the approve step: two admins approving overlapping
     * selections must serialise, or both could claim the same entry into
     * different batches — the same read-check-write race as order status,
     * solved the same way (SELECT ... FOR UPDATE, ascending id via the
     * caller sorting its input).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM VendorPayoutEntry e WHERE e.id IN :ids")
    List<VendorPayoutEntry> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    List<VendorPayoutEntry> findByBatchIdOrderByVendorIdAscIdAsc(Long batchId);
}
