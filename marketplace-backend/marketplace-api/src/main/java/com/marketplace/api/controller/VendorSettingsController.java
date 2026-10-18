package com.marketplace.api.controller;

import com.marketplace.api.dto.VendorSettingsDtos.AcceptTermsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.BankingDetailsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.VendorSettingsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.VendorSettingsResponse;
import com.marketplace.api.entity.User;
import com.marketplace.api.payout.BankListService;
import com.marketplace.api.payout.BankListService.BankOption;
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
    private final BankListService bankList;

    public VendorSettingsController(UserRepository userRepository,
                                    VendorPayoutSettingsService payoutSettings,
                                    BankListService bankList) {
        this.userRepository = userRepository;
        this.payoutSettings = payoutSettings;
        this.bankList = bankList;
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

    /**
     * The bank picker's options: the provider's verification-enabled SA
     * banks, cached 24h. 503 with a readable problem when the provider is
     * down and nothing is cached; never a built-in list.
     */
    @GetMapping("/payouts/banks")
    public java.util.List<BankOption> banks() {
        return bankList.banks();
    }

    @PutMapping("/payouts/banking")
    public PayoutSettingsStatus updateBanking(@Valid @RequestBody BankingDetailsRequest request,
                                              @AuthenticationPrincipal UserPrincipal me) {
        return payoutSettings.updateBanking(me.getId(),
                request.accountHolderName(), request.bankName(),
                request.accountNumber(), request.branchCode(), request.bankCode(),
                request.accountType(), request.identity());
    }

    @PostMapping("/payouts/accept-terms")
    public PayoutSettingsStatus acceptTerms(@Valid @RequestBody AcceptTermsRequest request,
                                            @AuthenticationPrincipal UserPrincipal me) {
        return payoutSettings.acceptTerms(me.getId(), request.version());
    }
}
