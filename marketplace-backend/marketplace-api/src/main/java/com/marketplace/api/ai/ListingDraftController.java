package com.marketplace.api.ai;

import com.marketplace.api.ai.ListingDraftDtos.ListingDraft;
import com.marketplace.api.security.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Vendor listing drafter: one photo in, one reviewed draft out.
 *
 * This endpoint PERSISTS NOTHING. It returns a suggestion the vendor edits and
 * submits through the ordinary create-product flow, which is unchanged and
 * still the only path that writes a row. That separation is the whole safety
 * model: AI-authored text cannot reach the catalogue without a human
 * submitting it, so there is no code path where a hallucinated claim is
 * published by omission.
 */
@RestController
public class ListingDraftController {

    private final ListingDraftService draftService;
    private final DraftRateLimiter rateLimiter;

    public ListingDraftController(ListingDraftService draftService,
                                  DraftRateLimiter rateLimiter) {
        this.draftService = draftService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Rate limit is consumed BEFORE the provider call, keyed on the caller.
     * Consuming after would mean a vendor whose drafts all fail upstream never
     * spends budget while still costing money on every attempt.
     */
    @PostMapping("/api/v1/vendor/products/draft")
    @PreAuthorize("hasAnyRole('VENDOR', 'ADMIN')")
    public ListingDraft draft(@RequestParam("file") MultipartFile file,
                              @AuthenticationPrincipal UserPrincipal me) {
        rateLimiter.checkAndConsume(me.getId());
        return draftService.draft(file);
    }
}
