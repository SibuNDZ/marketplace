package com.marketplace.api.payout;

import com.marketplace.api.dto.PayoutDtos.BatchSummary;
import com.marketplace.api.dto.PayoutDtos.PendingEntry;
import com.marketplace.api.dto.PayoutDtos.VendorPendingGroup;
import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutBatch;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.payout.NedbankBulkPaymentsExporter.PayoutLine;
import com.marketplace.api.payout.PayoutExceptions.InvalidPayoutStateException;
import com.marketplace.api.payout.PayoutExceptions.PayoutBatchNotFoundException;
import com.marketplace.api.payout.PayoutExceptions.VendorNotPayableException;
import com.marketplace.api.repository.VendorPayoutBatchRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The payout run: pending -> approve (batch) -> export (bank file) -> paid.
 *
 * Money leaves through a HUMAN uploading the exported file to NetBank and
 * authorising it — deliberately (vendor-payouts.md: "the authorisation stays
 * human… at this volume that is a control, not a limitation"). This service
 * only records that run truthfully: who approved, when it was exported, who
 * marked it paid, under what bank reference.
 */
@Service
public class PayoutAdminService {

    private static final Logger log = LoggerFactory.getLogger(PayoutAdminService.class);

    /** Statuses an admin can select into a batch. */
    private static final Set<PayoutEntryStatus> PAYABLE =
            EnumSet.of(PayoutEntryStatus.PENDING, PayoutEntryStatus.ADJUSTED);

    private final VendorPayoutEntryRepository entryRepository;
    private final VendorPayoutBatchRepository batchRepository;
    private final NedbankBulkPaymentsExporter exporter;

    public PayoutAdminService(VendorPayoutEntryRepository entryRepository,
                              VendorPayoutBatchRepository batchRepository,
                              NedbankBulkPaymentsExporter exporter) {
        this.entryRepository = entryRepository;
        this.batchRepository = batchRepository;
        this.exporter = exporter;
    }

    @Transactional(readOnly = true)
    public List<VendorPendingGroup> pending() {
        Map<Long, List<VendorPayoutEntry>> byVendor = groupByVendor(
                entryRepository.findPayable(PAYABLE));

        List<VendorPendingGroup> groups = new ArrayList<>();
        for (List<VendorPayoutEntry> entries : byVendor.values()) {
            User vendor = entries.get(0).getVendor();
            BigDecimal total = entries.stream()
                    .map(VendorPayoutEntry::getNetPayable)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new VendorPendingGroup(
                    vendor.getId(),
                    displayName(vendor),
                    BankingMask.of(vendor),
                    entries.stream().map(PayoutAdminService::toPendingEntry).toList(),
                    total));
        }
        return groups;
    }

    /**
     * Approves a selection of payable entries into a new batch.
     *
     * Refuses, rather than silently trims, when the selection is wrong:
     * an entry that is not payable (already batched, voided, paid) fails the
     * whole request, and so does a vendor whose selected sum is not positive —
     * a bank cannot transfer a negative amount, so a net-negative vendor's
     * adjustments CARRY FORWARD unbatched until future entries outweigh them.
     * Silent trimming would mean the admin approved something other than what
     * they saw on screen.
     */
    @Transactional
    public BatchSummary approve(List<Long> entryIds, Long adminId) {
        // Ascending id before locking — the same deadlock-avoidance ordering
        // rule as OrderService.lockAndRefresh.
        List<Long> sorted = entryIds.stream().distinct().sorted().toList();
        List<VendorPayoutEntry> entries = entryRepository.findAllByIdForUpdate(sorted);

        if (entries.size() != sorted.size()) {
            throw new InvalidPayoutStateException(
                    "Selection contains unknown payout entries");
        }
        for (VendorPayoutEntry e : entries) {
            if (!PAYABLE.contains(e.getStatus())) {
                throw new InvalidPayoutStateException(
                        "Entry " + e.getId() + " is " + e.getStatus()
                        + " and cannot be approved (only PENDING/ADJUSTED can)");
            }
        }

        for (Map.Entry<Long, List<VendorPayoutEntry>> group : groupByVendor(entries).entrySet()) {
            BigDecimal sum = group.getValue().stream()
                    .map(VendorPayoutEntry::getNetPayable)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.signum() <= 0) {
                throw new VendorNotPayableException(
                        "Vendor " + displayName(group.getValue().get(0).getVendor())
                        + "'s selected total is " + sum
                        + "; non-positive amounts carry forward instead of being batched");
            }
        }

        VendorPayoutBatch batch = new VendorPayoutBatch();
        batch.setApprovedBy(adminId);
        batch.setApprovedAt(LocalDateTime.now());
        batch = batchRepository.save(batch);

        for (VendorPayoutEntry e : entries) {
            e.setStatus(PayoutEntryStatus.APPROVED);
            e.setBatchId(batch.getId());
        }
        log.info("Payout batch {} approved by admin {}: {} entries", batch.getId(), adminId, entries.size());
        return summarise(batch, entries);
    }

    /**
     * The bank file. Contains FULL account numbers by necessity — this is the
     * one surface masking does not apply to, which is why it is generated on
     * demand, never stored, and only reachable through the admin guard.
     *
     * Exporting requires every vendor in the batch to have complete banking
     * details; refusing here beats a bank rejecting half a file after upload.
     * Re-export is allowed (files get lost); exported_at tracks the LATEST.
     */
    @Transactional
    public String exportCsv(Long batchId) {
        VendorPayoutBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new PayoutBatchNotFoundException(batchId));
        if (batch.getPaidAt() != null) {
            throw new InvalidPayoutStateException(
                    "Batch " + batchId + " is already paid; re-exporting a paid batch risks a double transfer");
        }
        List<VendorPayoutEntry> entries = entryRepository.findByBatchIdOrderByVendorIdAscIdAsc(batchId);
        if (entries.isEmpty()) {
            throw new InvalidPayoutStateException("Batch " + batchId + " has no entries");
        }

        List<PayoutLine> lines = new ArrayList<>();
        String period = batch.getApprovedAt().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (Map.Entry<Long, List<VendorPayoutEntry>> group : groupByVendor(entries).entrySet()) {
            User vendor = group.getValue().get(0).getVendor();
            if (!vendor.hasCompleteBankingDetails()) {
                throw new VendorNotPayableException(
                        "Vendor " + displayName(vendor) + " has incomplete banking details; "
                        + "the batch cannot be exported until they are captured");
            }
            BigDecimal amount = group.getValue().stream()
                    .map(VendorPayoutEntry::getNetPayable)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Reference = vendor code + payout period: what the vendor sees
            // on their bank statement, and what support greps for later.
            lines.add(new PayoutLine(
                    vendor.getAccountHolderName(),
                    vendor.getAccountNumber(),
                    vendor.getBranchCode(),
                    amount,
                    "ERESTYU-V" + vendor.getId() + "-" + period));
        }

        batch.setExportedAt(LocalDateTime.now());
        log.info("Payout batch {} exported: {} vendor line(s)", batchId, lines.size());
        return exporter.export(lines);
    }

    @Transactional
    public BatchSummary markPaid(Long batchId, String paymentReference, Long adminId) {
        VendorPayoutBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new PayoutBatchNotFoundException(batchId));
        if (batch.getPaidAt() != null) {
            throw new InvalidPayoutStateException("Batch " + batchId + " is already marked paid");
        }
        if (batch.getExportedAt() == null) {
            // You cannot have paid a file that was never generated.
            throw new InvalidPayoutStateException(
                    "Batch " + batchId + " has not been exported; export and upload the bank file first");
        }

        LocalDateTime now = LocalDateTime.now();
        batch.setPaidBy(adminId);
        batch.setPaidAt(now);
        batch.setPaymentReference(paymentReference);

        List<VendorPayoutEntry> entries = entryRepository.findByBatchIdOrderByVendorIdAscIdAsc(batchId);
        for (VendorPayoutEntry e : entries) {
            e.setStatus(PayoutEntryStatus.PAID);
            e.setPaymentReference(paymentReference);
            e.setPaidAt(now);
        }
        log.info("Payout batch {} marked paid by admin {} (ref {}): {} entries",
                batchId, adminId, paymentReference, entries.size());
        return summarise(batch, entries);
    }

    @Transactional(readOnly = true)
    public List<BatchSummary> batches() {
        return batchRepository.findAllByOrderByIdDesc().stream()
                .map(b -> summarise(b,
                        entryRepository.findByBatchIdOrderByVendorIdAscIdAsc(b.getId())))
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Map<Long, List<VendorPayoutEntry>> groupByVendor(List<VendorPayoutEntry> entries) {
        Map<Long, List<VendorPayoutEntry>> byVendor = new LinkedHashMap<>();
        for (VendorPayoutEntry e : entries) {
            byVendor.computeIfAbsent(e.getVendor().getId(), k -> new ArrayList<>()).add(e);
        }
        return byVendor;
    }

    private static String displayName(User vendor) {
        return vendor.getBusinessName() != null && !vendor.getBusinessName().isBlank()
                ? vendor.getBusinessName()
                : vendor.getFirstName() + " " + vendor.getLastName();
    }

    private static PendingEntry toPendingEntry(VendorPayoutEntry e) {
        return new PendingEntry(
                e.getId(),
                e.getOrder().getId(),
                e.getOrder().getOrderNumber(),
                e.getKind().name(),
                e.getCreatedAt(),
                e.getItemSubtotal(),
                e.getDeliveryFee(),
                e.getCommissionAmount(),
                e.getNetPayable(),
                e.getNote());
    }

    private static BatchSummary summarise(VendorPayoutBatch batch, List<VendorPayoutEntry> entries) {
        BigDecimal total = entries.stream()
                .map(VendorPayoutEntry::getNetPayable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long vendors = entries.stream().map(e -> e.getVendor().getId()).distinct().count();
        String state = batch.getPaidAt() != null ? "PAID"
                : batch.getExportedAt() != null ? "EXPORTED"
                : "APPROVED";
        return new BatchSummary(
                batch.getId(),
                batch.getApprovedAt(),
                batch.getExportedAt(),
                batch.getPaidAt(),
                batch.getPaymentReference(),
                entries.size(),
                (int) vendors,
                total,
                state);
    }
}
