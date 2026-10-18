package com.marketplace.api.payout;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderDeliveryFee;
import com.marketplace.api.entity.OrderItem;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-vendor arithmetic of an order, in ONE place (paystack-subaccounts.md
 * §6.1): the ledger entry written at PAID and the flat split sent at
 * initialize both come from here, so they are equal by construction.
 *
 *   commission = item_subtotal * rate, rounded DOWN to 2dp
 *   net        = item_subtotal - commission + delivery_fee
 *
 * Rate resolution: the vendor's override (users.commission_rate) first, the
 * platform default second. Delivery fees come from the order's snapshot
 * rows, never the vendor's current fee. Rounding DOWN on the commission
 * means the vendor is never paid less than the exact split; the platform
 * absorbs the sub-cent. (Moved verbatim from CommissionLedgerService.)
 *
 * Pure: reads the order graph, writes nothing, throws nothing. An item
 * whose product no longer resolves to a vendor is logged and skipped, as
 * the ledger always did; it must not vanish silently.
 */
@Component
public class VendorShareCalculator {

    private static final Logger log = LoggerFactory.getLogger(VendorShareCalculator.class);

    public record VendorShare(
            User vendor,
            BigDecimal itemSubtotal,
            BigDecimal deliveryFee,
            BigDecimal rate,
            BigDecimal commission,
            BigDecimal netPayable
    ) {
        public Long vendorId() {
            return vendor.getId();
        }
    }

    private final BigDecimal defaultRate;

    public VendorShareCalculator(@Value("${app.payouts.commission-rate}") BigDecimal defaultRate) {
        if (defaultRate.signum() < 0 || defaultRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(
                    "app.payouts.commission-rate must be a fraction in [0,1], got " + defaultRate);
        }
        this.defaultRate = defaultRate;
    }

    public BigDecimal defaultRate() {
        return defaultRate;
    }

    /** One share per vendor, in first-appearance order of the order's items. */
    public List<VendorShare> sharesFor(Order order) {
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

        Map<Long, BigDecimal> feesByVendor = new LinkedHashMap<>();
        for (OrderDeliveryFee fee : order.getDeliveryFees()) {
            if (fee.getVendor() != null) {
                feesByVendor.put(fee.getVendor().getId(), fee.getFeeAtPurchase());
            }
        }

        List<VendorShare> shares = new ArrayList<>();
        for (Map.Entry<Long, User> v : vendorsById.entrySet()) {
            User vendor = v.getValue();
            BigDecimal subtotal = subtotals.get(v.getKey());
            BigDecimal fee = feesByVendor.getOrDefault(v.getKey(), BigDecimal.ZERO);
            BigDecimal rate = vendor.getCommissionRate() != null ? vendor.getCommissionRate() : defaultRate;
            BigDecimal commission = subtotal.multiply(rate).setScale(2, RoundingMode.DOWN);
            shares.add(new VendorShare(vendor, subtotal, fee, rate, commission,
                    subtotal.subtract(commission).add(fee)));
        }
        return shares;
    }
}
