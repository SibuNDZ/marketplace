package com.marketplace.api.discovery;

import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ProductService;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Serving layer for the discovery feature.
 *
 * Route design:
 *   GET  /api/v1/products/popular?by=sales|rating|views   PUBLIC
 *        The home-page shelf rows and the cold-start fallback for
 *        everything personalized in Phase 2. Covered by the existing
 *        GET /api/v1/products/** permit in SecurityConfig.
 *   GET  /api/v1/me/recently-viewed                       auth
 *   GET  /api/v1/me/favorites                             auth
 *   PUT  /api/v1/products/{id}/favorite                   auth, idempotent
 *   DELETE /api/v1/products/{id}/favorite                 auth, idempotent
 *   The /me/** routes fall to anyRequest().authenticated() — verify with
 *   the 401 test in DiscoveryTest.
 *
 * SQL-safety: the ?by= parameter value NEVER touches the query string
 * directly. It is switched through a whitelist to a column literal, and
 * only that literal is formatted in. See the switch block in popular().
 *
 * toResponse delegates to ProductService (which owns the mapping) rather
 * than duplicating it — two copies will drift.
 */
@RestController
public class DiscoveryController {

    private static final int SHELF_SIZE = 12;

    private final EntityManager em;
    private final ProductRepository productRepository;
    private final ProductViewRepository viewRepository;
    private final FavoriteService favoriteService;
    private final ProductService productService;

    public DiscoveryController(EntityManager em,
                               ProductRepository productRepository,
                               ProductViewRepository viewRepository,
                               FavoriteService favoriteService,
                               ProductService productService) {
        this.em = em;
        this.productRepository = productRepository;
        this.viewRepository = viewRepository;
        this.favoriteService = favoriteService;
        this.productService = productService;
    }

    @GetMapping("/api/v1/products/popular")
    public List<ProductResponse> popular(@RequestParam(defaultValue = "sales") String by) {
        // Whitelist switch — NEVER format the raw parameter into SQL.
        // orderColumn is one of three string literals; the user's input
        // is discarded and the literal is what enters the query.
        String orderColumn = switch (by) {
            case "rating" -> "weighted_rating";
            case "views"  -> "views_30d";
            default       -> "sales_count";
        };

        @SuppressWarnings("unchecked")
        List<Number> ids = em.createNativeQuery("""
                SELECT pp.product_id FROM product_popularity pp
                JOIN products p ON p.id = pp.product_id
                WHERE p.deleted_at IS NULL
                ORDER BY pp.%s DESC, pp.product_id ASC
                LIMIT :n
                """.formatted(orderColumn))
                .setParameter("n", SHELF_SIZE)
                .getResultList();

        return inOrder(ids.stream().map(Number::longValue).toList());
    }

    @GetMapping("/api/v1/me/recently-viewed")
    public List<ProductResponse> recentlyViewed(@AuthenticationPrincipal UserPrincipal me) {
        List<Long> ids = viewRepository.recentProductIds(
                me.getId(), PageRequest.of(0, SHELF_SIZE));
        return inOrder(ids);   // soft-deleted products filtered in inOrder
    }

    @GetMapping("/api/v1/me/favorites")
    public Page<ProductResponse> favorites(
            @AuthenticationPrincipal UserPrincipal me,
            @PageableDefault(size = 20) Pageable pageable) {
        // Page-shaped batch mapping — one popularity query for the whole page,
        // not one per favorite (see ProductPopularityRepository).
        return productService.toResponses(
                favoriteService.list(me.getId(), pageable).map(Favorite::getProduct));
    }

    @PutMapping("/api/v1/products/{id}/favorite")
    public ResponseEntity<Void> favorite(@PathVariable Long id,
                                         @AuthenticationPrincipal UserPrincipal me) {
        favoriteService.add(me.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/products/{id}/favorite")
    public ResponseEntity<Void> unfavorite(@PathVariable Long id,
                                           @AuthenticationPrincipal UserPrincipal me) {
        favoriteService.remove(me.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Restores the ranked order that findAllById does not guarantee. */
    private List<ProductResponse> inOrder(List<Long> ids) {
        Map<Long, Product> byId = productRepository.findAllById(ids).stream()
                .filter(p -> p.getDeletedAt() == null)
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Product> ordered = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        // Batch mapping: one popularity query for the shelf, not one per product.
        return productService.toResponses(ordered);
    }
}
