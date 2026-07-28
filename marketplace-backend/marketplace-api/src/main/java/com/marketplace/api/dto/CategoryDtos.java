package com.marketplace.api.dto;

import java.util.List;

/**
 * Category read contracts. The tree is served whole, once, rather than as
 * a roots endpoint plus a children-of endpoint — it is ~41 rows and every
 * catalogue page needs the chip row anyway.
 */
public class CategoryDtos {

    /**
     * One node of the browse tree.
     *
     * productCount is the SUBTREE total, not the direct count: Produce
     * showing 0 while Fruit and veg beneath it shows 12 is the kind of
     * number that makes people distrust the whole page. Subcategory nodes
     * have no children, so for them subtree and direct are the same thing.
     *
     * It is computed per request from a GROUP BY rather than read from a
     * denormalised column. At this catalogue size the count is an index
     * scan over a handful of rows, whereas a stored counter has to be kept
     * true across create, delete, soft-delete, restore, and recategorise —
     * five write paths, any one of which drifts silently. When the count
     * genuinely costs something, the fix is a materialised view or a
     * counter column refreshed by a job (the pattern PopularityJob already
     * uses), and this record does not change shape either way.
     */
    public record CategoryNode(
            Long id,
            String slug,
            String name,
            String icon,
            long productCount,
            List<CategoryNode> children
    ) {}

    /** Flat summary used where the tree is overkill, e.g. the vendor product form. */
    public record CategoryOption(
            Long id,
            String slug,
            String name,
            String parentSlug
    ) {}
}
