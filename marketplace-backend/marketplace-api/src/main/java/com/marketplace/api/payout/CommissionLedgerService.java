package com.marketplace.api.payout;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderDeliveryFee;
import com.marketplace.api.entity.OrderItem;
import com.marketplace.api.entity.PayoutEntryKind;
import com.marketplace.api.entity.PayoutEntryStatus;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the commission ledger: one PRIMARY entry per (order, vendor) at the
 * moment an order goes PAID, and the reversal/adjustment rows that keep the
 * ledger honest afterwards.
 *
 * ARITHMETIC — the single statement of it, so it cannot drift:
 *
 *   commission_amount = item_subtotal * commission_rate, rounded DOWN to 2dp
 *   net_payable       = item_subtotal - commission_amount + delivery_fee
 *
 * Delivery fees pass through in full, never commissioned (vendor-payouts.md
 * §5: "the vendor receives exactly their expected share"). Rounding is DOWN,
 * not HALF_UP, and that is deliberate: the same §5 rule says the platform
 * absorbs sub-cent noise, and HALF_UP would shave up to half a cent OFF the
 * vendor on half the orders. DOWN on the commission means the vendor is never
 * paid less than the exact split; the platform's commission loses at most a
 * fraction of a cent per entry.
 *
 * IDEMPOTENCY: the primary guard is upstream — PaymentEventService holds the
 * order row lock and early-returns on already-PAID, so recordOnPaid runs at
 * most once per successful transition. The existsByOrderId check here covers
 * the backfill path, and V27's partial unique index is the loud last resort.
 *
 * RATE RESOLUTION: vendor override first (users.commission_rate), platform
 * default second (app.payouts.commission-rate). The resolved rate is
 * snapshotted onto the entry, so later rate changes never rewrite history —
 * the same principle as OrderItem.priceAtPurchase.
 */
@Service
public class CommissionLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CommissionLedgerService.class);

    private final VendorPayoutEntryRepository entryRepository;
    private final OrderRepository orderRepository;
    private final BigDecimal defaultRate;

    public CommissionLedgerService(VendorPayoutEntryRepository entryRepository,
                                   OrderRepository orderRepository,
                                   @Value("${app.payouts.commission-rate}") BigDecimal defaultRate) {
        if (defaultRate.signum() < 0 || defaultRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(
                    "app.payouts.commission-rate must be a fraction in [0,1], got " + defaultRate);
        }
        this.entryRepository = entryRepository;
        this.orderRepository = orderRepository;
        this.defaultRate = defaultRate;
    }

    /**
     * Called from PaymentEventService inside the PENDING->PAID transaction:
     * the order is row-locked and freshly PAID. If the ledger write fails,
     * the whole transition rolls back and the provider retries — an order is
     * never PAID without its ledger entries.
     */
    @Transactional
    public void recordOnPaid(Order order) {
        if (entryRepository.existsByOrderId(order.getId())) {
            log.info("Payout entries already exist for order {} - skipping", order.getId());
            return;
        }
        List<VendorPayoutEntry> entries = computeEntries(order);
        entryRepository.saveAll(entries);
        for (VendorPayoutEntry e : entries) {
            log.info("Payout entry: order {} vendor {} subtotal {} fee {} rate {} commission {} net {}",
                    order.getId(), e.getVendor().getId(), e.getItemSubtotal(), e.getDeliveryFee(),
                    e.getCommissionRate(), e.getCommissionAmount(), e.getNetPayable());
        }
    }

    /** Backfill entry point: loads inside its own transaction, then delegates. */
    @Transactional
    public void recordOnPaidById(Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order " + orderId));
        recordOnPaid(order);
    }

    /**
     * Dry-run companion to {@link #recordOnPaidById}: computes what WOULD be
     * written, as loggable lines, and writes nothing.
     */
    @Transactional(readOnly = true)
    public List<String> previewById(Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order " + orderId));
        if (entryRepository.existsByOrderId(orderId)) {
            return List.of();
        }
        return computeEntries(order).stream()
                .map(e -> "order %d vendor %d: subtotal %s fee %s rate %s commission %s net %s".formatted(
                        orderId, e.getVendor().getId(), e.getItemSubtotal(), e.getDeliveryFee(),
                        e.getCommissionRate(), e.getCommissionAmount(), e.getNetPayable()))
                .toList();
    }

    /**
     * Full-refund reversal, called when an order transitions to REFUNDED.
     *
     * Two cases per vendor, decided by whether money already left:
     *  - Rows not yet paid out (PENDING, APPROVED, ADJUSTED) -> VOID. Nothing
     *    moved, nothing to recover. APPROVED gets a WARN: its batch may
     *    already be exported as a bank file, and that file must be checked
     *    before upload.
     *  - Rows already PAID -> a clawback ADJUSTMENT negating the SUM of what
     *    was actually paid for the pair. Negating only the primary would
     *    over-claw when a partial-refund adjustment was already settled.
     *
     * An order with no entries at all (placed before the ledger existed, or
     * refunded pre-PAID) logs and returns — a refund must never fail because
     * history predates the ledger.
     */
    @Transactional
    public void reverseOnRefund(Order order) {
        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(order.getId());
        if (entries.isEmpty()) {
            log.info("Refund of order {} has no payout entries to reverse (pre-ledger order)",
                    order.getId());
            return;
        }

        Map<Long, List<VendorPayoutEntry>> byVendor = new LinkedHashMap<>();
        for (VendorPayoutEntry e : entries) {
            byVendor.computeIfAbsent(e.getVendor().getId(), k -> new ArrayList<>()).add(e);
        }

        List<VendorPayoutEntry> clawbacks = new ArrayList<>();
        for (List<VendorPayoutEntry> vendorEntries : byVendor.values()) {
            BigDecimal paidSubtotal   = BigDecimal.ZERO;
            BigDecimal paidFee        = BigDecimal.ZERO;
            BigDecimal paidCommission = BigDecimal.ZERO;
            BigDecimal paidNet        = BigDecimal.ZERO;
            VendorPayoutEntry sample  = null;

            for (VendorPayoutEntry e : vendorEntries) {
                switch (e.getStatus()) {
                    case PENDING, ADJUSTED -> {
                        e.setStatus(PayoutEntryStatus.VOID);
                        e.setNote(appendNote(e.getNote(), "Voided: order refunded before payout"));
                    }
                    case APPROVED -> {
                        e.setStatus(PayoutEntryStatus.VOID);
                        e.setNote(appendNote(e.getNote(),
                                "Voided: order refunded while approved in batch " + e.getBatchId()));
                        log.warn("Voided APPROVED payout entry {} (order {}, vendor {}) - "
                                        + "batch {} may already be exported; CHECK THE BANK FILE",
                                e.getId(), order.getId(), e.getVendor().getId(), e.getBatchId());
                    }
                    case PAID -> {
                        paidSubtotal   = paidSubtotal.add(e.getItemSubtotal());
                        paidFee        = paidFee.add(e.getDeliveryFee());
                        paidCommission = paidCommission.add(e.getCommissionAmount());
                        paidNet        = paidNet.add(e.getNetPayable());
                        sample = e;
                    }
                    case VOID -> { /* already terminal */ }
                }
            }

            if (sample != null && paidNet.signum() != 0) {
                VendorPayoutEntry clawback = new VendorPayoutEntry();
                clawback.setOrder(order);
                clawback.setVendor(sample.getVendor());
                clawback.setKind(PayoutEntryKind.ADJUSTMENT);
                clawback.setStatus(PayoutEntryStatus.ADJUSTED);
                clawback.setItemSubtotal(paidSubtotal.negate());
                clawback.setDeliveryFee(paidFee.negate());
                clawback.setCommissionRate(sample.getCommissionRate());
                clawback.setCommissionAmount(paidCommission.negate());
                clawback.setNetPayable(paidNet.negate());
                clawback.setNote("Clawback: full refund after payout");
                clawbacks.add(clawback);
                log.warn("Clawback of {} against vendor {} (order {} refunded after payout)",
                        paidNet, sample.getVendor().getId(), order.getId());
            }
        }
        entryRepository.saveAll(clawbacks);
    }

    /**
     * Partial-refund adjustment. DORMANT BY DESIGN: no partial-refund flow
     * exists in the codebase yet, so nothing calls this over HTTP — it is the
     * ledger mechanism, built and tested now so the future refund feature
     * lands on arithmetic that already works, not the other way round.
     *
     * @param refundedItemAmount  the refunded portion of the vendor's item
     *                            subtotal (a POSITIVE number)
     * @param refundedDeliveryFee the refunded portion of their delivery fee
     *                            (POSITIVE, usually zero)
     *
     * Commission comes back proportionally at the ORIGINAL entry's snapshotted
     * rate — a partial refund must not be an opportunity for the current rate
     * to leak into old orders.
     */
    @Transactional
    public VendorPayoutEntry recordAdjustment(Long orderId, Long vendorId,
                                              BigDecimal refundedItemAmount,
                                              BigDecimal refundedDeliveryFee,
                                              String note) {
        VendorPayoutEntry primary = entryRepository.findByOrderId(orderId).stream()
                .filter(e -> e.getKind() == PayoutEntryKind.PRIMARY
                        && e.getVendor().getId().equals(vendorId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No primary payout entry for order " + orderId + " vendor " + vendorId));

        BigDecimal commissionBack = refundedItemAmount
                .multiply(primary.getCommissionRate())
                .setScale(2, RoundingMode.DOWN);

        VendorPayoutEntry adjustment = new VendorPayoutEntry();
        adjustment.setOrder(primary.getOrder());
        adjustment.setVendor(primary.getVendor());
        adjustment.setKind(PayoutEntryKind.ADJUSTMENT);
        adjustment.setStatus(PayoutEntryStatus.ADJUSTED);
        adjustment.setItemSubtotal(refundedItemAmount.negate());
        adjustment.setDeliveryFee(refundedDeliveryFee.negate());
        adjustment.setCommissionRate(primary.getCommissionRate());
        adjustment.setCommissionAmount(commissionBack.negate());
        // Same invariant as everywhere: net = subtotal - commission + fee.
        adjustment.setNetPayable(refundedItemAmount.negate()
                .subtract(commissionBack.negate())
                .add(refundedDeliveryFee.negate()));
        adjustment.setNote(note);
        return entryRepository.save(adjustment);
    }

    // ── the computation ──────────────────────────────────────────────────

    private List<VendorPayoutEntry> computeEntries(Order order) {
        // Vendor is reached via product -> vendor (OrderItem does not snapshot
        // it; the ledger entry becomes the snapshot). product_id is nullable
        // in order_items — an item whose product was deleted between placement
        // and payment cannot be attributed and MUST NOT vanish silently.
        Map<Long, User> vendorsById = new LinkedHashMap<>();
        Map<Long, BigDecimal> subtotals = new LinkedHashMap<>();
        for (OrderItem item : order.getOrderItems()) {
            Product product = item.getProduct();
            User vendor = product == null ? null : product.getVendor();
            if (vendor == null) {
                log.error("PAYOUT ATTRIBUTION FAILED: order {} item '{}' ({} x {}) has no "
                                + "resolvable vendor - MANUAL ATTRIBUTION REQUIRED",
                        order.getId(), item.getProductNameAtPurchase(),
                        item.getQuantity(), item.getPriceAtPurchase());
                continue;
            }
            vendorsById.putIfAbsent(vendor.getId(), vendor);
            subtotals.merge(vendor.getId(),
                    item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())),
                    BigDecimal::add);
        }

        // Fees come from the snapshot table, never recomputed from the
        // vendor's current fee. Zero-fee vendors have no row ("free delivery
        // is silence" — V16), hence getOrDefault below.
        Map<Long, BigDecimal> feesByVendor = new LinkedHashMap<>();
        for (OrderDeliveryFee fee : order.getDeliveryFees()) {
            if (fee.getVendor() != null) {
                feesByVendor.put(fee.getVendor().getId(), fee.getFeeAtPurchase());
            }
        }

        List<VendorPayoutEntry> entries = new ArrayList<>();
        for (Map.Entry<Long, User> v : vendorsById.entrySet()) {
            User vendor = v.getValue();
            BigDecimal subtotal = subtotals.get(v.getKey());
            BigDecimal fee = feesByVendor.getOrDefault(v.getKey(), BigDecimal.ZERO);
            BigDecimal rate = vendor.getCommissionRate() != null
                    ? vendor.getCommissionRate() : defaultRate;
            BigDecimal commission = subtotal.multiply(rate).setScale(2, RoundingMode.DOWN);

            VendorPayoutEntry entry = new VendorPayoutEntry();
            entry.setOrder(order);
            entry.setVendor(vendor);
            entry.setKind(PayoutEntryKind.PRIMARY);
            entry.setStatus(PayoutEntryStatus.PENDING);
            entry.setItemSubtotal(subtotal);
            entry.setDeliveryFee(fee);
            entry.setCommissionRate(rate);
            entry.setCommissionAmount(commission);
            entry.setNetPayable(subtotal.subtract(commission).add(fee));
            entries.add(entry);
        }
        return entries;
    }

    private static String appendNote(String existing, String addition) {
        return existing == null || existing.isBlank() ? addition : existing + "; " + addition;
    }
}
