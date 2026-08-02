package com.marketplace.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public class ReviewDtos {

    public record CreateReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 1000) String comment
    ) {}

    public record ReviewResponse(
            Long id,
            Long productId,
            Long reviewerId,
            String reviewerName,
            int rating,
            String comment,
            LocalDateTime createdAt
    ) {}

    /**
     * Aggregate for product pages: "4.3 ★ (127 reviews)", plus what the
     * CALLER may do about it.
     *
     * Eligibility rides on this response rather than a separate endpoint or
     * a frontend guess: reviewing requires a delivered purchase and is
     * one-per-product, so a page that just renders a form invites people to
     * write something and then rejects it with a 403 or 409. The page
     * already fetches this summary, so honesty costs no extra round trip.
     * Both caller fields are false/null for anonymous visitors.
     */
    public record ReviewSummary(
            Long productId,
            double averageRating,
            long reviewCount,
            /** Caller has a delivered purchase and has not reviewed yet. */
            boolean canReview,
            /** Set when the caller already reviewed, so the UI offers edit. */
            Long myReviewId
    ) {}
}
