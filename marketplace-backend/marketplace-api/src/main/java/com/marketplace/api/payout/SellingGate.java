package com.marketplace.api.payout;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.payout.PayoutExceptions.VendorNotSellableException;
import com.marketplace.api.payout.PayoutExceptions.VendorNotSellableException.BlockedItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The selling gate: a vendor who has not accepted the current payout terms
 * or has incomplete banking details cannot be CHECKED OUT from.
 *
 * Enforced at placeOrder, server-side, deliberately NOT at add-to-cart: a
 * vendor can become ungated while their products already sit in carts (terms
 * version bump, banking detail removed), so the only check that cannot be
 * stale is the one at the moment money is about to be requested. The shopper
 * gets the same 409-with-items shape as a stock shortage — from their seat
 * it IS the same event.
 *
 * DEFAULT OFF (app.payouts.selling-gate-enabled): while the commission rate
 * and payout window are placeholders, gating would make every existing
 * vendor unsellable under terms quoting numbers nobody decided. Enabling the
 * gate is the same owner action as setting the real rate — see
 * application.yml. The enforcement path below is fully built and tested
 * either way; the flag only chooses whether it fires.
 */
@Component
public class SellingGate {

    private final boolean enabled;
    private final PayoutTerms terms;

    public SellingGate(@Value("${app.payouts.selling-gate-enabled}") boolean enabled,
                       PayoutTerms terms) {
        this.enabled = enabled;
        this.terms = terms;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when this vendor may currently be sold from (gate considered). */
    public boolean sellable(User vendor) {
        if (!enabled) {
            return true;
        }
        return vendor.hasAcceptedPayoutTerms(terms.version())
                && vendor.hasCompleteBankingDetails();
    }

    /**
     * Checkout's question, for the whole cart at once: throws with EVERY
     * blocked item listed, not just the first — the shopper fixes their cart
     * in one pass, the same courtesy InsufficientStockException extends.
     */
    public void assertSellable(Collection<Product> products) {
        if (!enabled) {
            return;
        }
        List<BlockedItem> blocked = new ArrayList<>();
        for (Product product : products) {
            User vendor = product.getVendor();
            if (vendor != null && !sellable(vendor)) {
                String vendorName = vendor.getBusinessName() != null && !vendor.getBusinessName().isBlank()
                        ? vendor.getBusinessName()
                        : vendor.getFirstName() + " " + vendor.getLastName();
                blocked.add(new BlockedItem(vendorName, product.getName()));
            }
        }
        if (!blocked.isEmpty()) {
            throw new VendorNotSellableException(blocked);
        }
    }
}
