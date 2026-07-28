package com.marketplace.api.controller;

import com.marketplace.api.dto.CategoryDtos.CategoryNode;
import com.marketplace.api.dto.CategoryDtos.CategoryOption;
import com.marketplace.api.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, unauthenticated reads — the chip row and sidebar render before
 * anyone logs in. Needs a permitAll carve-out in SecurityConfig alongside
 * the GET /products one.
 *
 * No write endpoints yet. Categories being a table is what makes an admin
 * CRUD screen possible later; this slice delivers the read side the
 * catalogue actually needs and does not speculate on the editing UI.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * The browse tree with subtree product counts.
     *
     * includeEmpty defaults FALSE so the shopper-facing default never
     * offers a category that leads nowhere. The vendor product form passes
     * true, because a brand-new category legitimately has no products yet
     * and still has to be selectable — otherwise nothing could ever be the
     * first product in a category.
     */
    @GetMapping
    public List<CategoryNode> tree(
            @RequestParam(defaultValue = "false") boolean includeEmpty) {
        return categoryService.tree(includeEmpty);
    }

    /** Flat list for the vendor product form's category picker. */
    @GetMapping("/options")
    public List<CategoryOption> options() {
        return categoryService.options();
    }
}
