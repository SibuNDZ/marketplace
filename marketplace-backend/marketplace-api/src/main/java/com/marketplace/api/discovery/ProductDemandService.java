package com.marketplace.api.discovery;

import com.marketplace.api.repository.OrderItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "In demand. N people bought this in the last 24 hours."
 *
 * SHIPPED INERT, ON PURPOSE. Production has had three orders in its entire
 * history, so this returns nothing today and the product page renders no
 * urgency line at all. That is the intended state, not a gap: the plumbing
 * exists now so the signal starts working the day the traffic is real, with
 * nobody having to remember to come back and wire it up.
 *
 * THE THRESHOLD IS THE WHOLE POINT. "1 person bought this in the last 24
 * hours" is worse than silence — it is an urgency badge that advertises an
 * empty shop, and a shopper reads it as exactly that. Below the floor this
 * returns null and the frontend renders nothing, which is the same rule the
 * rest of the site already follows: Top Selling hides itself until sales
 * exist, and cards say "No reviews yet" rather than inventing a score.
 *
 * The number is never rounded up, bucketed, or "starting from" padded. If it
 * says four people, four different people bought it.
 */
@Service
public class ProductDemandService {

    private final OrderItemRepository orderItemRepository;
    private final int minBuyers;

    public ProductDemandService(OrderItemRepository orderItemRepository,
                                @Value("${app.discovery.demand.min-buyers:3}") int minBuyers) {
        this.orderItemRepository = orderItemRepository;
        this.minBuyers = minBuyers;
    }

    /** Buyer count in the trailing 24h, or null when it is not worth saying. */
    @Transactional(readOnly = true)
    @Nullable
    public Long recentBuyers(Long productId) {
        long buyers = orderItemRepository.countDistinctRecentBuyers(productId);
        return buyers >= minBuyers ? buyers : null;
    }
}
