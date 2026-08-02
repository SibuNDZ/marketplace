package com.marketplace.api.feedback;

import com.marketplace.api.entity.PlatformFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * The operator's inbox. Double-gated like AdminOrderController: the
 * /api/v1/admin/** URL rule in SecurityConfig requires ROLE_ADMIN before
 * routing, and @PreAuthorize repeats it here so the protection survives a
 * SecurityConfig refactor.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
@PreAuthorize("hasRole('ADMIN')")
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    public AdminFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    public record FeedbackSummary(
            Long id,
            String userEmail,
            String category,
            String message,
            String status,
            LocalDateTime createdAt
    ) {
        static FeedbackSummary from(PlatformFeedback f) {
            return new FeedbackSummary(
                    f.getId(),
                    f.getUser().getEmail(),
                    f.getCategory().name(),
                    f.getMessage(),
                    f.getStatus().name(),
                    f.getCreatedAt());
        }
    }

    @GetMapping
    public Page<FeedbackSummary> list(
            @RequestParam(required = false) PlatformFeedback.Status status,
            @PageableDefault(size = 50) Pageable pageable) {
        // Ordering lives in the repository method names (newest first);
        // the Pageable only carries page/size.
        return feedbackService.list(status, pageable).map(FeedbackSummary::from);
    }

    /** Idempotent: already-reviewed also returns 204, not an error. */
    @PostMapping("/{id}/reviewed")
    public ResponseEntity<Void> markReviewed(@PathVariable Long id) {
        feedbackService.markReviewed(id);
        return ResponseEntity.noContent().build();
    }
}
