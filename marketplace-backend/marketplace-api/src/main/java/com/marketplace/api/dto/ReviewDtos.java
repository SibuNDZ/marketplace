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

    /** Aggregate for product pages: "4.3 ★ (127 reviews)". */
    public record ReviewSummary(
            Long productId,
            double averageRating,
            long reviewCount
    ) {}
}
