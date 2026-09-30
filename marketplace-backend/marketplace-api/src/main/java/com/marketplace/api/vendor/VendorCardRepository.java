package com.marketplace.api.vendor;

import com.marketplace.api.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The public facts about a store, in one query per call: who they are (name,
 * bio, picture) and what their live listings say (pieces, units sold, rating,
 * one photo to show).
 *
 * Every figure is computed from the same sources the rest of the site uses,
 * so the spotlight can never disagree with the product cards:
 *  - pieces: live (not soft-deleted) products
 *  - sold:   product_popularity.sales_count, which counts kept sales only
 *            (PAID/SHIPPED/DELIVERED, never a test order)
 *  - rating: the plain mean over every review of their live products, which
 *            is the review-count-weighted mean of the per-product averages
 *
 * The name follows User.getStorefrontName(): business name, else the
 * person's full name.
 */
public interface VendorCardRepository extends Repository<User, Long> {

    interface VendorCard {
        Long getId();
        String getName();
        String getBio();
        String getAvatarKey();
        long getPieces();
        long getSold();
        BigDecimal getRating();
        long getReviewCount();
        /** Newest live listing's first photo; null if none of their listings has one. */
        String getSampleKey();
    }

    String CARD_SELECT = """
            SELECT u.id AS id,
                   COALESCE(NULLIF(TRIM(u.business_name), ''),
                            TRIM(u.first_name || ' ' || u.last_name)) AS name,
                   u.bio AS bio,
                   u.avatar_key AS avatarKey,
                   (SELECT COUNT(*) FROM products p
                     WHERE p.vendor_id = u.id AND p.deleted_at IS NULL) AS pieces,
                   (SELECT COALESCE(SUM(pp.sales_count), 0) FROM product_popularity pp
                      JOIN products p ON p.id = pp.product_id
                     WHERE p.vendor_id = u.id AND p.deleted_at IS NULL) AS sold,
                   (SELECT ROUND(AVG(r.rating)::numeric, 2) FROM reviews r
                      JOIN products p ON p.id = r.product_id
                     WHERE p.vendor_id = u.id AND p.deleted_at IS NULL) AS rating,
                   (SELECT COUNT(*) FROM reviews r
                      JOIN products p ON p.id = r.product_id
                     WHERE p.vendor_id = u.id AND p.deleted_at IS NULL) AS reviewCount,
                   (SELECT pi.image_key FROM product_images pi
                      JOIN products p ON p.id = pi.product_id
                     WHERE p.vendor_id = u.id AND p.deleted_at IS NULL
                     ORDER BY p.created_at DESC, pi.position ASC, pi.id ASC
                     LIMIT 1) AS sampleKey
            FROM users u
            """;

    /** Any vendor, for their shop page. Empty for a non-vendor id. */
    @Query(nativeQuery = true, value = CARD_SELECT + """
            WHERE u.id = :id AND u.role = 'VENDOR'
            """)
    Optional<VendorCard> findCard(@Param("id") Long id);

    /**
     * Stores eligible for the home page spotlight: an active vendor with at
     * least one live listing that has a photo (a spotlight with nothing to
     * show is an empty box), excluding the seed account, which is not a real
     * store.
     *
     * Vendors who have filled in their profile (picture AND bio) come first,
     * so the spotlight leads with stores that have something to say; the
     * rest follow, newest vendor first. The client rotates through the list.
     */
    @Query(nativeQuery = true, value = CARD_SELECT + """
            WHERE u.role = 'VENDOR'
              AND COALESCE(u.is_active, TRUE)
              AND COALESCE(u.business_name, '') <> 'Fixture Vendor'
              AND EXISTS (SELECT 1 FROM products p
                            JOIN product_images pi ON pi.product_id = p.id
                           WHERE p.vendor_id = u.id AND p.deleted_at IS NULL)
            ORDER BY (u.avatar_key IS NOT NULL AND COALESCE(u.bio, '') <> '') DESC,
                     u.id DESC
            """)
    List<VendorCard> spotlightCards();
}
