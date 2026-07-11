package com.marketplace.api.entity;

/**
 * Mirror this list in the frontend's data/categories.ts EXACTLY (keys, not
 * labels) — the enum name is what Jackson serializes and what the frontend
 * uses as the category key, no translation layer between them.
 *
 * Adding a value here requires a migration updating products_category_check
 * (V10) in the same change — the V7 CHECK-constraint trap, restated.
 */
public enum ProductCategory {
    PRODUCE("Produce"),
    PANTRY("Pantry"),
    CRAFTS("Crafts"),
    HOME("Home"),
    OTHER("Other");

    private final String displayName;

    ProductCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
