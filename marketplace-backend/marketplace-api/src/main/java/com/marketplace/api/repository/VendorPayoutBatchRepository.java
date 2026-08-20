package com.marketplace.api.repository;

import com.marketplace.api.entity.VendorPayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorPayoutBatchRepository extends JpaRepository<VendorPayoutBatch, Long> {

    List<VendorPayoutBatch> findAllByOrderByIdDesc();
}
