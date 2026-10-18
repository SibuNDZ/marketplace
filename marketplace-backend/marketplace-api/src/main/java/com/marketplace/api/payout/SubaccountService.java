package com.marketplace.api.payout;

import com.marketplace.api.entity.User;
import com.marketplace.api.payment.PaystackClient;
import com.marketplace.api.payment.PaystackClient.CreateSubaccountRequest;
import com.marketplace.api.payment.PaystackClient.Subaccount;
import com.marketplace.api.payment.PaystackClient.UpdateSubaccountRequest;
import com.marketplace.api.payment.PaystackExceptions.PaystackRejectedException;
import com.marketplace.api.payment.PaystackExceptions.PaystackUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The vendor's Paystack subaccount (paystack-subaccounts.md §4.3): created
 * on approve, updated when the banking changes, read on demand.
 *
 * The commission is 10 as a true default on the subaccount
 * (percentage_charge); every transaction overrides it with a flat split
 * (§6.1), so the number matters only if a transaction ever reaches
 * Paystack without one.
 *
 * With the split switch off nothing here calls Paystack: approve behaves
 * as slice 1 left it, and a banking change updates nothing.
 */
@Service
public class SubaccountService {

    private static final Logger log = LoggerFactory.getLogger(SubaccountService.class);

    /** Spec §4.3. A true default even though every transaction overrides it. */
    static final int PERCENTAGE_CHARGE = 10;

    private final PaystackClient paystack;
    private final boolean splitEnabled;

    public SubaccountService(PaystackClient paystack,
                             @Value("${app.paystack.split-enabled:false}") boolean splitEnabled) {
        this.paystack = paystack;
        this.splitEnabled = splitEnabled;
    }

    public boolean isSplitEnabled() {
        return splitEnabled;
    }

    /**
     * Called inside the approve transaction. Creates the subaccount for a
     * vendor who has none, or re-sends the current banking to the one they
     * have (a banking change whose update failed is retried here, never
     * duplicated into a second subaccount). Any failure propagates so the
     * approve rolls back: {@link PaystackRejectedException} for a refusal,
     * {@link PaystackUnavailableException} for an outage.
     */
    public void ensureForApproved(User vendor) {
        if (!splitEnabled) {
            return;
        }
        if (vendor.getPaystackSubaccountCode() == null) {
            Subaccount created = paystack.createSubaccount(new CreateSubaccountRequest(
                    businessName(vendor),
                    vendor.getBankCode(),
                    vendor.getAccountNumber(),
                    PERCENTAGE_CHARGE,
                    vendor.getEmail(),
                    "eRestyu vendor " + vendor.getId(),
                    vendor.getId()));
            vendor.setPaystackSubaccountCode(created.code());
            log.info("Vendor {} subaccount created (verified={}, schedule={})",
                    vendor.getId(), created.isVerified(), created.settlementSchedule());
        } else {
            Subaccount updated = paystack.updateSubaccount(vendor.getPaystackSubaccountCode(),
                    new UpdateSubaccountRequest(businessName(vendor), vendor.getBankCode(),
                            vendor.getAccountNumber()));
            log.info("Vendor {} subaccount re-sent on approve (verified={})",
                    vendor.getId(), updated.isVerified());
        }
    }

    /**
     * Called from the banking save, OUTSIDE any decision: the banking must
     * save whatever Paystack does, so failures are returned as a message
     * for the verification row's note rather than thrown.
     *
     * @return null when nothing needed doing or the update succeeded; the
     *         failure reason otherwise
     */
    public String updateIfPresent(User vendor) {
        if (!splitEnabled || vendor.getPaystackSubaccountCode() == null) {
            return null;
        }
        try {
            paystack.updateSubaccount(vendor.getPaystackSubaccountCode(),
                    new UpdateSubaccountRequest(businessName(vendor), vendor.getBankCode(),
                            vendor.getAccountNumber()));
            log.info("Vendor {} subaccount updated after a banking change; Paystack holds payouts "
                    + "until the owner re-verifies it in the dashboard", vendor.getId());
            return null;
        } catch (PaystackRejectedException e) {
            log.warn("Vendor {} subaccount update refused: {}", vendor.getId(), e.getMessage());
            return "provider refused: " + e.getMessage();
        } catch (PaystackUnavailableException e) {
            log.warn("Vendor {} subaccount update unavailable: {}", vendor.getId(), e.getMessage());
            return "provider unavailable";
        }
    }

    /** GET /subaccount/{code}; throws the client's typed exceptions. */
    public Subaccount fetch(String code) {
        return paystack.fetchSubaccount(code);
    }

    /** Spec §4.3: the business name, or the person's name for an individual. */
    static String businessName(User vendor) {
        String name = vendor.getBusinessName();
        return name == null || name.isBlank() ? vendor.getFullName() : name.strip();
    }
}
