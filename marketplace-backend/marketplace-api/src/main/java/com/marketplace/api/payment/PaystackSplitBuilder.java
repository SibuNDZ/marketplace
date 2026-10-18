package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketplace.api.payout.VendorShareCalculator.VendorShare;

import java.util.List;

/**
 * The flat split object for a Paystack initialize request
 * (paystack-subaccounts.md §6.1), built from the same per-vendor shares the
 * ledger writes at PAID.
 *
 * Mixed rollout: a vendor WITHOUT a subaccount code is simply left out, so
 * their share stays with the main account and their ledger entry settles
 * by EFT as today. When no vendor in the order has a code there is no
 * split object at all and the request is byte-for-byte what it was.
 *
 * The sum of shares must not exceed the amount. A violation is OUR
 * arithmetic bug, so it is an exception (500 at the API, a Sentry event),
 * never a trimmed share quietly sent.
 */
public final class PaystackSplitBuilder {

    /** Spec §6.1 reference shape. */
    static final String REFERENCE_SUFFIX = "-SPLIT";

    /** Our arithmetic produced shares that exceed the charge. Fail loudly. */
    public static class SplitArithmeticException extends IllegalStateException {
        public SplitArithmeticException(String message) {
            super(message);
        }
    }

    private PaystackSplitBuilder() {}

    /**
     * @return the split object to put on the request, or null when no
     *         vendor in the order has a subaccount code
     */
    public static ObjectNode build(ObjectMapper mapper, Long orderId, long amountCents,
                                   List<VendorShare> shares) {
        ArrayNode subaccounts = mapper.createArrayNode();
        long sum = 0;
        for (VendorShare share : shares) {
            String code = share.vendor().getPaystackSubaccountCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            long cents = Money.toCents(share.netPayable());
            if (cents <= 0) {
                // A zero or negative share (free item with no fee, or an
                // unexpected adjustment) has nothing to settle by split.
                continue;
            }
            subaccounts.addObject().put("subaccount", code).put("share", cents);
            sum += cents;
        }
        if (subaccounts.isEmpty()) {
            return null;
        }
        if (sum > amountCents) {
            throw new SplitArithmeticException("Split shares for order " + orderId + " total " + sum
                    + " cents but the charge is " + amountCents + " cents; refusing to send");
        }
        ObjectNode split = mapper.createObjectNode();
        split.put("type", "flat");
        split.put("bearer_type", "account");
        split.set("subaccounts", subaccounts);
        split.put("reference", PaystackCheckoutService.REFERENCE_PREFIX + orderId + REFERENCE_SUFFIX);
        return split;
    }
}
