package com.marketplace.api.service;

import com.marketplace.api.dto.ReviewDtos.CreateReviewRequest;
import com.marketplace.api.dto.ReviewDtos.ReviewResponse;
import com.marketplace.api.dto.ReviewDtos.ReviewSummary;
import com.marketplace.api.entity.Review;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.exception.ReviewExceptions.DuplicateReviewException;
import com.marketplace.api.exception.ReviewExceptions.NotVerifiedPurchaserException;
import com.marketplace.api.exception.ReviewExceptions.ReviewNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ReviewRepository;
import com.marketplace.api.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase-verified reviews. The gate is a live QUERY over order history
 * (hasDeliveredPurchase), not a stored flag — it cannot drift from the truth
 * and costs one indexed lookup at write time, which is cheap.
 *
 * Duplicate handling is two-layer (same philosophy as stock locking): the
 * service check gives a clean 409 message; the V4 unique constraint is the
 * backstop under concurrent double-submit.  saveAndFlush forces the INSERT
 * inside the try block so a constraint race surfaces there and gets translated
 * to DuplicateReviewException, instead of escaping at commit time as an
 * unhandled rollback.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public ReviewService(ReviewRepository reviewRepository,
                         ProductRepository productRepository,
                         UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ReviewResponse create(Long productId, CreateReviewRequest request, Long userId) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
        if (!reviewRepository.hasDeliveredPurchase(userId, productId)) {
            throw new NotVerifiedPurchaserException(productId);
        }
        if (reviewRepository.existsByUserIdAndProductId(userId, productId)) {
            throw new DuplicateReviewException(productId);
        }

        Review review = new Review();
        review.setProduct(productRepository.getReferenceById(productId));
        review.setUser(userRepository.getReferenceById(userId));
        review.setRating(request.rating());
        review.setComment(request.comment());

        try {
            // saveAndFlush, not save: forces the INSERT now so a unique-
            // constraint race surfaces here, inside the try, rather than at
            // commit where it would escape as an unhandled rollback.
            return toResponse(reviewRepository.saveAndFlush(review));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateReviewException(productId);
        }
    }

    /** Author may edit their own review; rating and comment only. */
    @Transactional
    public ReviewResponse update(Long reviewId, CreateReviewRequest request, Long userId) {
        Review review = reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        review.setRating(request.rating());
        review.setComment(request.comment());
        return toResponse(review);
    }

    /**
     * Admins moderate any review; authors delete their own. Not-yours-and-not-
     * admin gets 404, not 403 — same information-hiding stance as orders.
     */
    @Transactional
    public void delete(Long reviewId, Long userId, boolean isAdmin) {
        Review review = (isAdmin
                ? reviewRepository.findById(reviewId)
                : reviewRepository.findByIdAndUserId(reviewId, userId))
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        reviewRepository.delete(review);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> listForProduct(Long productId, Pageable pageable) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
        return reviewRepository.findByProductId(productId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ReviewSummary summary(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
        Object[] agg = reviewRepository.ratingAggregate(productId);
        // JPQL multi-select returns Object[]; some providers nest it — unwrap defensively.
        Object[] row = (agg.length > 0 && agg[0] instanceof Object[]) ? (Object[]) agg[0] : agg;
        double avg = ((Number) row[0]).doubleValue();
        long count = ((Number) row[1]).longValue();
        return new ReviewSummary(productId, Math.round(avg * 10.0) / 10.0, count);
    }

    private ReviewResponse toResponse(Review r) {
        return new ReviewResponse(
                r.getId(),
                r.getProduct().getId(),
                r.getUser().getId(),
                r.getUser().getFirstName() + " " + r.getUser().getLastName(),
                r.getRating(),
                r.getComment(),
                r.getCreatedAt());
    }
}
