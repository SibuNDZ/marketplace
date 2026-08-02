package com.marketplace.api.feedback;

import com.marketplace.api.entity.PlatformFeedback;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submission endpoint. Authenticated, ANY role: restricting to VENDOR would
 * cost buyer goodwill for no gain — buyers may have useful critique too.
 * Rate limited per user (5/hour) in the service, purely against spam.
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    public record SubmitFeedbackRequest(
            @NotNull PlatformFeedback.Category category,
            @NotBlank @Size(max = 2000) String message
    ) {}

    public record SubmitFeedbackResponse(Long id) {}

    @PostMapping
    public ResponseEntity<SubmitFeedbackResponse> submit(
            @Valid @RequestBody SubmitFeedbackRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        PlatformFeedback saved = feedbackService.submit(me.getId(), request.category(), request.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubmitFeedbackResponse(saved.getId()));
    }
}
