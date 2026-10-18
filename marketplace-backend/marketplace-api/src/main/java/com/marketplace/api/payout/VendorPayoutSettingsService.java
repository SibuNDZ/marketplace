package com.marketplace.api.payout;

import com.marketplace.api.dto.PayoutDtos.MaskedBanking;
import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import com.marketplace.api.entity.VerificationProviderResult;
import com.marketplace.api.payment.PaystackExceptions.PaystackRejectedException;
import com.marketplace.api.payout.PayoutExceptions.StaleTermsVersionException;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorVerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Vendor self-service payout onboarding: read status, capture banking,
 * accept terms.
 *
 * Banking values pass through here on the way IN only; every response
 * carries the masked form (BankingMask, the one masking method). Nothing in
 * this class logs a banking value — log lines carry the vendor id and facts
 * ABOUT the change, never the change itself.
 *
 * A banking CHANGE (any of holder name, account number, bank code, branch
 * code, account type) clears the admin's approval and opens a new PENDING
 * verification row in the same transaction (paystack-subaccounts.md §4.4):
 * the account an admin approved is no longer the account on file.
 * Resubmitting identical values writes nothing. The row's provider_result
 * is NOT_RUN in this slice: the validate call does not exist yet, and the
 * row must not claim otherwise.
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
            boolean sellable,
            /** vendor_approved_at is set: an admin approved the banking on file. */
            boolean approved,
            /** The latest verification row's outcome, or NONE when there is no row. */
            String verificationState
    ) {}

    private final UserRepository userRepository;
    private final VendorVerificationRepository verifications;
    private final AccountValidationService validation;
    private final PayoutTerms terms;
    private final SellingGate gate;

    public VendorPayoutSettingsService(UserRepository userRepository,
                                       VendorVerificationRepository verifications,
                                       AccountValidationService validation,
                                       PayoutTerms terms,
                                       SellingGate gate) {
        this.userRepository = userRepository;
        this.verifications = verifications;
        this.validation = validation;
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
                                              String branchCode, String bankCode,
                                              BankAccountType accountType) {
        return updateBanking(vendorId, accountHolderName, bankName, accountNumber,
                branchCode, bankCode, accountType, null);
    }

    /**
     * With an optional identity document. The document number is used for
     * two things and nothing else: its last four characters go on the new
     * verification row, and, with the split switch on, the number goes to
     * the provider's validate call once (AccountValidationService). It is
     * not stored and not logged. A document that differs from the one on
     * the latest row counts as a change: the account must be re-verified
     * against it.
     */
    @Transactional
    public PayoutSettingsStatus updateBanking(Long vendorId, String accountHolderName,
                                              String bankName, String accountNumber,
                                              String branchCode, String bankCode,
                                              BankAccountType accountType,
                                              IdentityDocumentRequest identity) {
        User vendor = userRepository.findById(vendorId).orElseThrow();
        String holder = accountHolderName.strip();
        String number = accountNumber.strip();
        String branch = branchCode.strip();
        String bank = bankCode.strip();
        // bankName is the display label and is not part of what the provider
        // verifies, so changing it alone does not reopen verification.
        boolean changed = !Objects.equals(holder, vendor.getAccountHolderName())
                || !Objects.equals(number, vendor.getAccountNumber())
                || !Objects.equals(bank, vendor.getBankCode())
                || !Objects.equals(branch, vendor.getBranchCode())
                || accountType != vendor.getAccountType()
                || (identity != null && !identityOnLatestRow(vendorId, identity));

        vendor.setAccountHolderName(holder);
        vendor.setBankName(bankName.strip());
        vendor.setAccountNumber(number);
        vendor.setBranchCode(branch);
        vendor.setBankCode(bank);
        vendor.setAccountType(accountType);

        if (changed) {
            vendor.setVendorApprovedAt(null);
            VendorVerification row = new VendorVerification();
            row.setVendor(vendor);
            row.setAccountHolderName(holder);
            row.setBankCode(bank);
            row.setAccountType(accountType);
            row.setAccountLast4(number.substring(number.length() - 4));
            row.setProviderResult(VerificationProviderResult.NOT_RUN);
            row.setOutcome(VerificationOutcome.PENDING);
            if (identity != null) {
                row.setDocumentType(identity.type());
                row.setDocumentLast4(identity.last4());
                try {
                    // The one place the number is read; it is on the wire and gone.
                    validation.apply(row, vendor, identity.number());
                } catch (PaystackRejectedException refused) {
                    // Paystack understood and refused (a bad bank code, say).
                    // The vendor is never blocked by that: the banking saves,
                    // the row records why, and the admin re-runs or rejects.
                    row.setProviderResult(VerificationProviderResult.UNAVAILABLE);
                    row.setVerificationMessage("Provider refused the check: " + refused.getMessage());
                }
            }
            verifications.save(row);
        }
        // Deliberately no banking VALUES in this line — id and outcome only.
        log.info("Vendor {} updated banking details (complete={}, changed={})",
                vendorId, vendor.hasCompleteBankingDetails(), changed);
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

    /** True when the latest row already records this document's type and last four. */
    private boolean identityOnLatestRow(Long vendorId, IdentityDocumentRequest identity) {
        return verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendorId)
                .map(v -> v.getDocumentType() == identity.type()
                        && Objects.equals(v.getDocumentLast4(), identity.last4()))
                .orElse(false);
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
                gate.sellable(vendor),
                vendor.getVendorApprovedAt() != null,
                verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId())
                        .map(v -> v.getOutcome().name())
                        .orElse("NONE"));
    }
}
