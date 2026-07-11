package com.marketplace.api.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * findAllById is the whole point: one query enriches a full catalog page.
 * Never call this per-product in a loop — the batch shape is what keeps
 * signal enrichment at exactly one extra query per page.
 */
public interface ProductPopularityRepository extends JpaRepository<ProductPopularity, Long> {
}
