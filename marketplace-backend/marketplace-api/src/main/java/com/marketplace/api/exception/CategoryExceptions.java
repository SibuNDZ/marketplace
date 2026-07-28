package com.marketplace.api.exception;

public class CategoryExceptions {

    /**
     * Thrown for an unknown ?category= slug. A 404 rather than a silently
     * empty result set: ?category=jewelry (US spelling) returning "no
     * products" reads as an empty shop, which is a much worse lie than
     * telling the caller the category does not exist.
     */
    public static class CategoryNotFoundException extends RuntimeException {
        public CategoryNotFoundException(String slug) {
            super("No category with slug: " + slug);
        }
    }
}
