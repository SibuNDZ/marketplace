package com.marketplace.api.feedback;

import com.marketplace.api.entity.PlatformFeedback;
import com.marketplace.api.repository.PlatformFeedbackRepository;
import com.marketplace.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The private user-to-operator feedback channel. Deliberately the smallest
 * honest version: a form that lands in an admin inbox. Not public reviews,
 * not a forum, not threads.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final PlatformFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final FeedbackRateLimiter rateLimiter;

    public FeedbackService(PlatformFeedbackRepository feedbackRepository,
                           UserRepository userRepository,
                           FeedbackRateLimiter rateLimiter) {
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public PlatformFeedback submit(Long userId, PlatformFeedback.Category category, String message) {
        rateLimiter.checkAndConsume(userId);

        PlatformFeedback feedback = new PlatformFeedback();
        feedback.setUser(userRepository.getReferenceById(userId));
        feedback.setCategory(category);
        feedback.setMessage(message.strip());
        PlatformFeedback saved = feedbackRepository.save(feedback);
        log.info("Platform feedback {} ({}) submitted by user {}", saved.getId(), category, userId);

        // TODO(email): when the transactional-email slice grows an operator
        // notification, this is where "new feedback" hooks in — after the
        // save, async, swallow-and-log, same as the order emails.
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<PlatformFeedback> list(PlatformFeedback.Status status, Pageable pageable) {
        return status == null
                ? feedbackRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : feedbackRepository.findByStatusOrderByCreatedAtDescIdDesc(status, pageable);
    }

    /**
     * Idempotent by design: marking an already-reviewed row reviewed again is
     * a no-op success, not an error. Two admins triaging the same inbox should
     * never see a spurious failure because the other got there first.
     */
    @Transactional
    public void markReviewed(Long feedbackId) {
        PlatformFeedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new FeedbackNotFoundException(feedbackId));
        feedback.setStatus(PlatformFeedback.Status.REVIEWED);
    }

    public static class FeedbackNotFoundException extends RuntimeException {
        public FeedbackNotFoundException(Long id) {
            super("Feedback " + id + " not found");
        }
    }
}
