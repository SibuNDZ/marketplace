package com.marketplace.api.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * A browse category. Two levels: roots have a null parent, subcategories
 * point at a root. Depth is enforced by the database (V14 uses a composite
 * self-FK on (parent_id, parent_depth) -> (id, depth), so a third level
 * cannot be inserted at all) — this class does not re-litigate it, it just
 * cannot construct one that would survive a flush.
 *
 * Replaces the ProductCategory enum. The enum meant every category a vendor
 * asked for was a code change plus a migration plus a redeploy, and V10's
 * own comment flagged that cost. This is a table so it becomes an insert.
 *
 * slug, not id, is the public identifier: ?category=jewellery survives a
 * reseed, and it is what the frontend routes on. id stays internal.
 *
 * NOTE there is no productCount field. Counting is a live GROUP BY (see
 * CategoryService) rather than a denormalised column — same approach the
 * catalogue already used for its sidebar counts before this change.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Null for a top-level category. LAZY because rendering a subcategory
     * rarely needs its parent hydrated, and the tree endpoint loads every
     * row in one query anyway.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent")
    @OrderBy("sortOrder ASC, name ASC")
    private List<Category> children = new ArrayList<>();

    @Column(name = "name", nullable = false, length = 80)
    @NotBlank
    @Size(max = 80)
    private String name;

    // Lowercase, digits and hyphens only. Enforced here as well as by the
    // unique constraint because a slug with a space or a slash silently
    // breaks the ?category= URL rather than failing loudly at write time.
    @Column(name = "slug", nullable = false, length = 80, unique = true)
    @NotBlank
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
             message = "Slug must be lowercase words separated by hyphens")
    private String slug;

    /** Emoji, shown in the chip row. Null for subcategories by default. */
    @Column(name = "icon", length = 16)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /**
     * Admin-controlled visibility, NOT derived from product count.
     *
     * The original spec was to set this false for any category with zero
     * products. That breaks on a tree: Produce can hold zero products
     * DIRECTLY while Fruit and veg beneath it holds plenty, and hiding
     * Produce would strand its children. "Has anything to show" is a
     * question about the whole subtree and is answered per-request by the
     * count query; this flag is the separate, deliberate "we are not
     * running this category right now".
     */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Category getParent() { return parent; }
    public void setParent(Category parent) { this.parent = parent; }

    public List<Category> getChildren() { return children; }
    public void setChildren(List<Category> children) { this.children = children; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public boolean isTopLevel() { return parent == null; }

    @Override
    public String toString() {
        return "Category{id=" + id + ", slug='" + slug + "'}";
    }
}
