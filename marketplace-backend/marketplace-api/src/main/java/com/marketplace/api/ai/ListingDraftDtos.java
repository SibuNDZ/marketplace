package com.marketplace.api.ai;

public class ListingDraftDtos {

    /**
     * A suggestion, not a product. Nothing with this shape is ever persisted —
     * the vendor's own form submission is still the only thing that creates a
     * row, and it goes through the same validation it always did.
     *
     * Deliberately does NOT carry price, stock, sku, tags, or handmade:
     *   - price and stock are the vendor's commercial decisions and a
     *     hallucinated number there is the one that actually costs money.
     *   - sku is theirs to key against their own inventory.
     *   - tags and handmade are cheap for a vendor to set and expensive to get
     *     wrong (handmade is a trust signal buyers filter on).
     *
     * disclaimer travels with the payload rather than living only in the
     * frontend so any future consumer of this endpoint inherits the warning.
     */
    public record ListingDraft(
            String name,
            String description,
            String categorySlug,
            String disclaimer
    ) {}
}
