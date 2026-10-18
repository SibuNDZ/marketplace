package com.marketplace.api.payout;

import com.marketplace.api.dto.VendorVerificationDtos.DecisionResponse;
import com.marketplace.api.dto.VendorVerificationDtos.ListingSummary;
import com.marketplace.api.dto.VendorVerificationDtos.PendingVendor;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductImage;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import com.marketplace.api.entity.VerificationProviderResult;
import com.marketplace.api.payout.PayoutExceptions.VerificationNotApprovableException;
import com.marketplace.api.payout.PayoutExceptions.VerificationNotFoundException;
import com.marketplace.api.repository.ProductImageRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.VendorVerificationRepository;
import com.marketplace.api.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin review of vendor verification (paystack-subaccounts.md §4.3).
 *
 * The decision is written ONCE: approve and reject on a row that is
 * already decided return the current state and change nothing, so a
 * double click or a retried request cannot flip an outcome. Approve is
 * refused (409) when the provider said MISMATCH or was UNAVAILABLE; there
 * is no "approve anyway" path, and adding one is a change to the spec, not
 * a flag. NOT_RUN (this slice: the validate call does not exist yet) and
 * OK may be approved on the admin's own review of the holder name and the
 * vendor's first listings.
 *
 * Approve is the only writer of users.vendor_approved_at besides the clear
 * on a banking change. Reject leaves it alone: a pending vendor's
 * approval is already null.
 *
 * Nothing here logs or returns a full account number; the pending view is
 * built from the verification row's last-4 and the vendor's masked fields.
 */
@Service
public class VendorVerificationAdminService {

    private static final Logger log = LoggerFactory.getLogger(VendorVerificationAdminService.class);

    private final VendorVerificationRepository verifications;
    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ObjectStorageService storage;

    public VendorVerificationAdminService(VendorVerificationRepository verifications,
                                          ProductRepository productRepository,
                                          ProductImageRepository imageRepository,
                                          ObjectStorageService storage) {
        this.verifications = verifications;
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
    }

    /** Every vendor whose latest row is PENDING, oldest submission first. */
    @Transactional(readOnly = true)
    public List<PendingVendor> pending() {
        return verifications.findLatestWithOutcome(VerificationOutcome.PENDING).stream()
                .map(this::toPending)
                .toList();
    }

    @Transactional
    public DecisionResponse approve(Long vendorId, Long adminId, String note) {
        VendorVerification row = latest(vendorId);
        if (row.getOutcome() != VerificationOutcome.PENDING) {
            return toDecision(row); // already decided: idempotent, nothing changes
        }
        if (row.getProviderResult() == VerificationProviderResult.MISMATCH
                || row.getProviderResult() == VerificationProviderResult.UNAVAILABLE) {
            throw new VerificationNotApprovableException(vendorId, row.getProviderResult());
        }
        LocalDateTime now = LocalDateTime.now();
        decide(row, VerificationOutcome.APPROVED, adminId, now, note);
        row.getVendor().setVendorApprovedAt(now);
        log.info("Admin {} approved vendor {} (verification {}, provider result {})",
                adminId, vendorId, row.getId(), row.getProviderResult());
        return toDecision(row);
    }

    @Transactional
    public DecisionResponse reject(Long vendorId, Long adminId, String note) {
        VendorVerification row = latest(vendorId);
        if (row.getOutcome() != VerificationOutcome.PENDING) {
            return toDecision(row);
        }
        decide(row, VerificationOutcome.REJECTED, adminId, LocalDateTime.now(), note);
        log.info("Admin {} rejected vendor {} (verification {})", adminId, vendorId, row.getId());
        return toDecision(row);
    }

    private VendorVerification latest(Long vendorId) {
        return verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendorId)
                .orElseThrow(() -> new VerificationNotFoundException(vendorId));
    }

    private static void decide(VendorVerification row, VerificationOutcome outcome,
                               Long adminId, LocalDateTime at, String note) {
        row.setOutcome(outcome);
        row.setVerifiedBy(adminId);
        row.setVerifiedAt(at);
        if (note != null && !note.isBlank()) {
            row.setNote(note.strip());
        }
    }

    private PendingVendor toPending(VendorVerification v) {
        User vendor = v.getVendor();
        List<Product> products = productRepository
                .findTop5ByVendorIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(vendor.getId());
        Map<Long, String> cover = new HashMap<>();
        if (!products.isEmpty()) {
            List<ProductImage> images = imageRepository.findByProductIdInOrderByPositionAscIdAsc(
                    products.stream().map(Product::getId).toList());
            for (ProductImage img : images) {
                cover.putIfAbsent(img.getProduct().getId(), storage.publicUrl(img.getImageKey()));
            }
        }
        List<ListingSummary> listings = products.stream()
                .map(p -> new ListingSummary(p.getId(), p.getName(), p.getPrice(), cover.get(p.getId())))
                .toList();
        return new PendingVendor(
                vendor.getId(),
                vendor.getBusinessName(),
                vendor.getEmail(),
                v.getCreatedAt(),
                v.getAccountHolderName(),
                v.getBankCode(),
                vendor.getBankName(),
                mask(v.getAccountLast4()),
                v.getAccountType() == null ? null : v.getAccountType().name(),
                v.getDocumentType() == null ? null : v.getDocumentType().name(),
                mask(v.getDocumentLast4()),
                v.getProviderResult().name(),
                v.getVerificationMessage(),
                listings);
    }

    /** The BankingMask convention for a stored last-4: "···6789", null when absent. */
    private static String mask(String last4) {
        return last4 == null || last4.isBlank() ? null : "···" + last4;
    }

    private static DecisionResponse toDecision(VendorVerification row) {
        return new DecisionResponse(
                row.getVendor().getId(),
                row.getId(),
                row.getOutcome().name(),
                row.getProviderResult().name(),
                row.getVerifiedBy(),
                row.getVerifiedAt(),
                row.getNote(),
                row.getVendor().getVendorApprovedAt());
    }
}
