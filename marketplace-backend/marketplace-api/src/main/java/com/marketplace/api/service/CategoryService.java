package com.marketplace.api.service;

import com.marketplace.api.dto.CategoryDtos.CategoryNode;
import com.marketplace.api.dto.CategoryDtos.CategoryOption;
import com.marketplace.api.entity.Category;
import com.marketplace.api.exception.CategoryExceptions.CategoryNotFoundException;
import com.marketplace.api.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the browse tree and resolves the ?category= slug.
 *
 * Everything here is read-only and assembled in memory from two queries:
 * all categories, and live-product counts grouped by category id. That is
 * deliberate — the alternative (walking children per root) is an N+1 that
 * looks fine at 8 roots and stops looking fine the moment the taxonomy
 * grows, which is the entire reason categories became a table.
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * The public browse tree: active categories, each carrying its subtree
     * product count.
     *
     * includeEmpty=false drops any node whose subtree has no live products.
     * That is the "never show a dead end" rule from the spec, implemented
     * as a read-time filter rather than by flipping the active column:
     * emptiness changes every time a vendor lists or sells out, and a
     * column would need maintaining on all of those paths. active stays
     * what an admin decided; empty stays what the data says.
     *
     * A root survives if ANY of its children has products, even when the
     * root itself directly holds none — that is the case a naive
     * zero-count check gets wrong and it is why this is a subtree rule.
     */
    @Transactional(readOnly = true)
    public List<CategoryNode> tree(boolean includeEmpty) {
        List<Category> all = categoryRepository.findAllForTree();
        Map<Long, Long> direct = directCounts();

        Map<Long, List<Category>> childrenByParent = new HashMap<>();
        for (Category c : all) {
            if (c.getParent() != null) {
                childrenByParent
                        .computeIfAbsent(c.getParent().getId(), k -> new java.util.ArrayList<>())
                        .add(c);
            }
        }

        return all.stream()
                .filter(Category::isTopLevel)
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .sorted(bySortOrder())
                .map(root -> toNode(root, childrenByParent, direct))
                .filter(node -> includeEmpty || node.productCount() > 0)
                .toList();
    }

    /** Flat list for pickers. Subcategories only — a product belongs to a leaf. */
    @Transactional(readOnly = true)
    public List<CategoryOption> options() {
        return categoryRepository.findAllForTree().stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .sorted(Comparator
                        .comparing((Category c) -> c.isTopLevel() ? c.getSlug()
                                                                 : c.getParent().getSlug())
                        .thenComparing(Category::getSortOrder))
                .map(c -> new CategoryOption(
                        c.getId(), c.getSlug(), c.getName(),
                        c.isTopLevel() ? null : c.getParent().getSlug()))
                .toList();
    }

    /**
     * Resolve a ?category= slug to the ids a catalogue query should match.
     *
     * A root expands to ITSELF PLUS ITS CHILDREN: browsing Fashion must
     * show the jewellery, not an empty page because every product is
     * filed one level down. A subcategory resolves to just itself.
     */
    @Transactional(readOnly = true)
    public List<Long> resolveToIds(String slug) {
        Category category = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new CategoryNotFoundException(slug));

        if (!category.isTopLevel()) {
            return List.of(category.getId());
        }

        List<Long> ids = new java.util.ArrayList<>();
        ids.add(category.getId());
        categoryRepository.findByParentId(category.getId())
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    @Transactional(readOnly = true)
    public Category requireBySlug(String slug) {
        return categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new CategoryNotFoundException(slug));
    }

    private CategoryNode toNode(Category root,
                                Map<Long, List<Category>> childrenByParent,
                                Map<Long, Long> direct) {
        List<Category> kids = childrenByParent.getOrDefault(root.getId(), List.of());

        List<CategoryNode> childNodes = kids.stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .sorted(bySortOrder())
                .map(c -> new CategoryNode(
                        c.getId(), c.getSlug(), c.getName(), c.getIcon(),
                        direct.getOrDefault(c.getId(), 0L), List.of()))
                .toList();

        long subtreeCount = direct.getOrDefault(root.getId(), 0L)
                + childNodes.stream().mapToLong(CategoryNode::productCount).sum();

        return new CategoryNode(root.getId(), root.getSlug(), root.getName(),
                root.getIcon(), subtreeCount, childNodes);
    }

    private Map<Long, Long> directCounts() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : categoryRepository.countLiveProductsByCategoryId()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private Comparator<Category> bySortOrder() {
        return Comparator.comparing(Category::getSortOrder)
                .thenComparing(Category::getName);
    }
}
