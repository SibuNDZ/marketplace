package com.marketplace.api.repository;

import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VendorVerificationRepository extends JpaRepository<VendorVerification, Long> {

    /** The vendor's current state: newest row first (id breaks a same-instant tie). */
    Optional<VendorVerification> findFirstByVendorIdOrderByCreatedAtDescIdDesc(Long vendorId);

    /**
     * Every vendor whose LATEST row has the given outcome. The subquery
     * picks the newest row per vendor; an older PENDING row under a newer
     * decision is history, not work. Vendor fetched eagerly: the admin list
     * renders the business name and email on every row.
     */
    @Query("""
           SELECT v FROM VendorVerification v JOIN FETCH v.vendor
           WHERE v.outcome = :outcome
             AND v.id = (SELECT MAX(latest.id) FROM VendorVerification latest
                         WHERE latest.vendor = v.vendor)
           ORDER BY v.createdAt ASC
           """)
    List<VendorVerification> findLatestWithOutcome(@Param("outcome") VerificationOutcome outcome);
}
