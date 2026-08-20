package com.marketplace.api.payout;

import com.marketplace.api.dto.PayoutDtos.MaskedBanking;
import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.User;
import com.marketplace.api.payout.PayoutExceptions.StaleTermsVersionException;
import com.marketplace.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Vendor self-service payout onboarding: read status, capture banking,
 * accept terms.
 *
 * Banking values pass through here on the way IN only; every response
 * carries the masked form (BankingMask, the one masking method). Nothing in
 * this class logs a banking value — log lines carry the vendor id and facts
 * ABOUT the change, never the change itself.
 */
@Service
public class VendorPayoutSettingsService {

    private static final Logger log = LoggerFactory.getLogger(VendorPayoutSettingsService.class);

    /** What the vendor's settings screen needs to render, in one shape. */
    public record PayoutSettingsStatus(
            String termsText,
            int termsVersion,
            Integer acceptedVersion,
            java.time.LocalDateTime acceptedAt,
            boolean termsCurrent,
            MaskedBanking banking,
            boolean gateEnabled,
            /** False only when the gate is on and onboarding is incomplete. */
            boolean sellable
    ) {}

    private final UserRepository userRepository;
    private final PayoutTerms terms;
    private final SellingGate gate;

    public VendorPayoutSettingsService(UserRepository userRepository,
                                       PayoutTerms terms,
                                       SellingGate gate) {
        this.userRepository = userRepository;
        this.terms = terms;
        this.gate = gate;
    }

    @Transactional(readOnly = true)
    public PayoutSettingsStatus status(Long vendorId) {
        return toStatus(userRepository.findById(vendorId).orElseThrow());
    }

    @Transactional
    public PayoutSettingsStatus updateBanking(Long vendorId, String accountHolderName,
                                              String bankName, String accountNumber,
                                              String branchCode, BankAccountType accountType) {
        User vendor = userRepository.findById(vendorId).orElseThrow();
        vendor.setAccountHolderName(accountHolderName.strip());
        vendor.setBankName(bankName.strip());
        vendor.setAccountNumber(accountNumber.strip());
        vendor.setBranchCode(branchCode.strip());
        vendor.setAccountType(accountType);
        // Deliberately no banking VALUES in this line — id and outcome only.
        log.info("Vendor {} updated banking details (complete={})",
                vendorId, vendor.hasCompleteBankingDetails());
        return toStatus(vendor);
    }

    /**
     * Acceptance is OF A VERSION, echoed back by the client from the status
     * it rendered — if the config version moved between render and click,
     * the vendor accepted text nobody showed them, and that must fail
     * loudly rather than record a false consent.
     */
    @Transactional
    public PayoutSettingsStatus acceptTerms(Long vendorId, int versionSent) {
        if (versionSent != terms.version()) {
            throw new StaleTermsVersionException(versionSent, terms.version());
        }
        User vendor = userRepository.findById(vendorId).orElseThrow();
        vendor.setPayoutTermsVersion(terms.version());
        vendor.setPayoutTermsAcceptedAt(LocalDateTime.now());
        log.info("Vendor {} accepted payout terms v{}", vendorId, terms.version());
        return toStatus(vendor);
    }

    private PayoutSettingsStatus toStatus(User vendor) {
        return new PayoutSettingsStatus(
                terms.text(),
                terms.version(),
                vendor.getPayoutTermsVersion(),
                vendor.getPayoutTermsAcceptedAt(),
                vendor.hasAcceptedPayoutTerms(terms.version()),
                BankingMask.of(vendor),
                gate.isEnabled(),
                gate.sellable(vendor));
    }
}
