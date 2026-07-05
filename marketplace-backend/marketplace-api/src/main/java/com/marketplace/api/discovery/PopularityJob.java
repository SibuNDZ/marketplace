package com.marketplace.api.discovery;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Rebuilds the product_popularity read model. THE architecture rule of
 * the discovery feature: recommendations are precomputed, never computed
 * on a request path — serving is a sorted read of this table joined to
 * products.
 *
 * Three signals per product:
 *  - sales_count: SUM(quantity) over orders in PAID/SHIPPED/DELIVERED.
 *    PENDING (no money), CANCELLED (aborted), REFUNDED (un-sold) are all
 *    excluded — bestsellers rank KEPT sales.
 *  - weighted_rating: Bayesian shrinkage,
 *        WR = (v/(v+m))·R + (m/(v+m))·C
 *    where R = product's mean rating, v = its review count, m = prior
 *    weight (5), C = global mean rating across all products. One 5-star
 *    review scores as ~C nudged upward, not 5.0 — it cannot outrank a
 *    4.6 average with 20 reviews when the global mean is well below 4.6.
 *  - views_30d: raw view events in the rolling window (anonymous included).
 *
 * One native statement, set-based, INSERT … ON CONFLICT: the whole model
 * rebuilds in a single round trip regardless of catalog size. Native SQL
 * because JPQL has no upsert and this is a read-model rewrite, not entity
 * manipulation.
 *
 * Same single-instance note as OrderExpiryJob: add ShedLock before
 * horizontal scaling.
 */
@Component
public class PopularityJob {

    private static final Logger log = LoggerFactory.getLogger(PopularityJob.class);
    private static final int BAYESIAN_PRIOR_WEIGHT = 5;  // m
    private static final int VIEW_RETENTION_DAYS   = 90;

    private final EntityManager em;
    private final ProductViewRepository viewRepository;

    public PopularityJob(EntityManager em, ProductViewRepository viewRepository) {
        this.em = em;
        this.viewRepository = viewRepository;
    }

    @Scheduled(fixedDelayString = "${app.discovery.popularity-refresh-ms:3600000}")
    @Transactional
    public void rebuild() {
        MDC.put("requestId", "job-popularity-" + UUID.randomUUID());
        try {
            long t0 = System.currentTimeMillis();
            int rows = em.createNativeQuery("""
                    INSERT INTO product_popularity
                        (product_id, sales_count, review_count, avg_rating,
                         weighted_rating, views_30d, computed_at)
                    SELECT
                        p.id,
                        COALESCE(s.units, 0),
                        COALESCE(r.cnt, 0),
                        COALESCE(r.avg_r, 0),
                        CASE WHEN COALESCE(r.cnt, 0) = 0 THEN 0 ELSE
                            (r.cnt / CAST(r.cnt + :m AS NUMERIC)) * r.avg_r
                          + (:m   / CAST(r.cnt + :m AS NUMERIC)) * g.global_avg
                        END,
                        COALESCE(v.views, 0),
                        now()
                    FROM products p
                    CROSS JOIN (
                        SELECT COALESCE(AVG(rating), 0) AS global_avg FROM reviews
                    ) g
                    LEFT JOIN (
                        SELECT oi.product_id, SUM(oi.quantity) AS units
                        FROM order_items oi
                        JOIN orders o ON o.id = oi.order_id
                        WHERE o.status IN ('PAID', 'SHIPPED', 'DELIVERED')
                        GROUP BY oi.product_id
                    ) s ON s.product_id = p.id
                    LEFT JOIN (
                        SELECT product_id,
                               COUNT(*)    AS cnt,
                               AVG(rating) AS avg_r
                        FROM reviews GROUP BY product_id
                    ) r ON r.product_id = p.id
                    LEFT JOIN (
                        SELECT product_id, COUNT(*) AS views
                        FROM product_views
                        WHERE viewed_at > now() - INTERVAL '30 days'
                        GROUP BY product_id
                    ) v ON v.product_id = p.id
                    ON CONFLICT (product_id) DO UPDATE SET
                        sales_count     = EXCLUDED.sales_count,
                        review_count    = EXCLUDED.review_count,
                        avg_rating      = EXCLUDED.avg_rating,
                        weighted_rating = EXCLUDED.weighted_rating,
                        views_30d       = EXCLUDED.views_30d,
                        computed_at     = EXCLUDED.computed_at
                    """)
                    .setParameter("m", BAYESIAN_PRIOR_WEIGHT)
                    .executeUpdate();
            log.info("Popularity model rebuilt: {} products in {}ms",
                    rows, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Popularity rebuild failed", e);  // → Sentry via min-event-level
        } finally {
            MDC.remove("requestId");
        }
    }

    /** Nightly at 03:15 — bounded table, enforced retention (POPIA). */
    @Scheduled(cron = "${app.discovery.view-retention-cron:0 15 3 * * *}")
    @Transactional
    public void sweepOldViews() {
        MDC.put("requestId", "job-viewsweep-" + UUID.randomUUID());
        try {
            int deleted = viewRepository.deleteOlderThan(
                    LocalDateTime.now().minusDays(VIEW_RETENTION_DAYS));
            log.info("View retention sweep: {} rows older than {} days deleted",
                    deleted, VIEW_RETENTION_DAYS);
        } finally {
            MDC.remove("requestId");
        }
    }
}
