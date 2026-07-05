package com.marketplace.api.controller;

import com.marketplace.api.dto.ReviewDtos.CreateReviewRequest;
import com.marketplace.api.dto.ReviewDtos.ReviewResponse;
import com.marketplace.api.dto.ReviewDtos.ReviewSummary;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Reads and creation nest under the product (reviews are product-scoped facts);
 * edit/delete address the review by its own id.
 *
 * Security: SecurityConfig already permits GET /api/v1/products/** and
 * GET /api/v1/reviews/** — the GET routes below are public. POST falls to
 * anyRequest().authenticated(), returning 401 for unauthenticated callers.
 */
@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    // ---- product-scoped: browse + create ----

    @GetMapping("/api/v1/products/{productId}/reviews")
    public Page<ReviewResponse> list(
            @PathVariable Long productId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return reviewService.listForProduct(productId, pageable);
    }

    @GetMapping("/api/v1/products/{productId}/reviews/summary")
    public ReviewSummary summary(@PathVariable Long productId) {
        return reviewService.summary(productId);
    }

    @PostMapping("/api/v1/products/{productId}/reviews")
    public ResponseEntity<ReviewResponse> create(
            @PathVariable Long productId,
            @Valid @RequestBody CreateReviewRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        ReviewResponse created = reviewService.create(productId, request, me.getId());
        return ResponseEntity
                .created(URI.create("/api/v1/reviews/" + created.id()))
                .body(created);
    }

    // ---- review-scoped: author edit, author/admin delete ----

    @PutMapping("/api/v1/reviews/{id}")
    public ReviewResponse update(
            @PathVariable Long id,
            @Valid @RequestBody CreateReviewRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return reviewService.update(id, request, me.getId());
    }

    @DeleteMapping("/api/v1/reviews/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal me) {
        reviewService.delete(id, me.getId(), "ADMIN".equals(me.getRole()));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
