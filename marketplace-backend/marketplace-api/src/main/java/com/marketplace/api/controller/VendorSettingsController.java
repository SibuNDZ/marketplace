package com.marketplace.api.controller;

import com.marketplace.api.dto.VendorSettingsDtos.AcceptTermsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.BankingDetailsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.VendorSettingsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.VendorSettingsResponse;
import com.marketplace.api.entity.User;
import com.marketplace.api.payout.VendorPayoutSettingsService;
import com.marketplace.api.payout.VendorPayoutSettingsService.PayoutSettingsStatus;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Vendor self-service settings. The delivery fee kept its original no-service
 * shape; the payout endpoints below are the "second concern" that earned a
 * service (VendorPayoutSettingsService) — banking and terms have rules worth
 * keeping out of a controller.
 */
@RestController
@RequestMapping("/api/v1/vendor/settings")
@PreAuthorize("hasRole('VENDOR')")
public class VendorSettingsController {

    private final UserRepository userRepository;
    private final VendorPayoutSettingsService payoutSettings;

    public VendorSettingsController(UserRepository userRepository,
                                    VendorPayoutSettingsService payoutSettings) {
        this.userRepository = userRepository;
        this.payoutSettings = payoutSettings;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public VendorSettingsResponse get(@AuthenticationPrincipal UserPrincipal me) {
        User vendor = userRepository.findById(me.getId()).orElseThrow();
        return new VendorSettingsResponse(vendor.getDeliveryFee());
    }

    @PutMapping
    @Transactional
    public VendorSettingsResponse update(@Valid @RequestBody VendorSettingsRequest request,
                                         @AuthenticationPrincipal UserPrincipal me) {
        User vendor = userRepository.findById(me.getId()).orElseThrow();
        vendor.setDeliveryFee(request.deliveryFee());
        return new VendorSettingsResponse(vendor.getDeliveryFee());
    }

    // ── payout onboarding (terms + banking) ────────────────────────────
    // Responses carry banking MASKED (last 4); the full values go in only.

    @GetMapping("/payouts")
    public PayoutSettingsStatus payoutStatus(@AuthenticationPrincipal UserPrincipal me) {
        return payoutSettings.status(me.getId());
    }

    @PutMapping("/payouts/banking")
    public PayoutSettingsStatus updateBanking(@Valid @RequestBody BankingDetailsRequest request,
                                              @AuthenticationPrincipal UserPrincipal me) {
        return payoutSettings.updateBanking(me.getId(),
                request.accountHolderName(), request.bankName(),
                request.accountNumber(), request.branchCode(), request.accountType());
    }

    @PostMapping("/payouts/accept-terms")
    public PayoutSettingsStatus acceptTerms(@Valid @RequestBody AcceptTermsRequest request,
                                            @AuthenticationPrincipal UserPrincipal me) {
        return payoutSettings.acceptTerms(me.getId(), request.version());
    }
}
