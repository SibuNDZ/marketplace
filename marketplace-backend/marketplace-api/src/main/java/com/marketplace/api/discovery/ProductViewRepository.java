package com.marketplace.api.discovery;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductViewRepository extends JpaRepository<ProductView, Long> {

    /**
     * Recently-viewed: latest distinct products for a user, newest first.
     * GROUP BY + MAX deduplicates repeated visits at read time, keeping
     * the event table raw and honest (don't coalesce events on write —
     * raw data is the input to every future signal).
     */
    @Query("""
            SELECT pv.product.id FROM ProductView pv
            WHERE pv.user.id = :userId
            GROUP BY pv.product.id
            ORDER BY MAX(pv.viewedAt) DESC
            """)
    List<Long> recentProductIds(@Param("userId") Long userId, Pageable limit);

    /** POPIA retention sweep — enforces the 90-day window, keeps table bounded. */
    @Modifying
    @Query("DELETE FROM ProductView pv WHERE pv.viewedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
