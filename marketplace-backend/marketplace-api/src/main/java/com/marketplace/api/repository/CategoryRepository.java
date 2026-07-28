package com.marketplace.api.repository;

import com.marketplace.api.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Children of one root — used to expand a top-level ?category= filter. */
    List<Category> findByParentId(Long parentId);

    /**
     * Every category in one query, parent eagerly joined.
     *
     * The tree endpoint is called on nearly every catalogue page load and
     * there are ~41 rows total, so fetching all of them once and assembling
     * the tree in memory beats a root query plus a children query per root.
     * The LEFT JOIN FETCH on parent is what stops the assembly step from
     * lazy-loading each parent back out of the database — without it this
     * is a textbook N+1 that only shows up once the taxonomy grows.
     */
    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.sortOrder ASC, c.name ASC")
    List<Category> findAllForTree();

    /**
     * Live-product counts keyed by category id.
     *
     * Direct counts only — a product sits in exactly one category, so a
     * product under Fruit and veg is NOT counted against Produce here.
     * Rolling children up into their parent is done in CategoryService,
     * because that is a shape question about the tree, not a storage one.
     */
    @Query("""
           SELECT p.category.id, COUNT(p)
           FROM Product p
           WHERE p.deletedAt IS NULL
           GROUP BY p.category.id
           """)
    List<Object[]> countLiveProductsByCategoryId();
}
