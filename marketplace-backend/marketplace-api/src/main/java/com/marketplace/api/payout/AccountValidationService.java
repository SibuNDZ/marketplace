package com.marketplace.api.payout;

import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationProviderResult;
import com.marketplace.api.payment.PaystackClient;
import com.marketplace.api.payment.PaystackClient.ValidateRequest;
import com.marketplace.api.payment.PaystackClient.ValidateResult;
import com.marketplace.api.payment.PaystackExceptions.PaystackRejectedException;
import com.marketplace.api.payment.PaystackExceptions.PaystackUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs the provider's bank-account validation for one verification row
 * (paystack-subaccounts.md §4.2) and writes the result onto it:
 *
 *   OK          verified AND accountHolderMatch true
 *   MISMATCH    the call succeeded but either is false
 *   UNAVAILABLE Paystack unreachable, timed out, or 5xx (the row and the
 *               banking are still saved: a vendor is never blocked by a
 *               third party being down)
 *
 * The document number arrives as a method argument and leaves in the
 * request body; it is never stored, never logged, and this class holds no
 * reference to it after the call. When the split switch is off the row is
 * left NOT_RUN and Paystack is not called at all, so a deployed
 * environment with the switch off behaves exactly as slice 1 left it.
 */
@Service
public class AccountValidationService {

    private static final Logger log = LoggerFactory.getLogger(AccountValidationService.class);

    private final PaystackClient paystack;
    private final boolean splitEnabled;

    public AccountValidationService(PaystackClient paystack,
                                    @Value("${app.paystack.split-enabled:false}") boolean splitEnabled) {
        this.paystack = paystack;
        this.splitEnabled = splitEnabled;
    }

    public boolean isSplitEnabled() {
        return splitEnabled;
    }

    /**
     * @param documentNumber the number as submitted, or null for an admin
     *                       re-run (we do not have it; Paystack marks it
     *                       optional, FIND OUT C1 records what comes back)
     * @throws PaystackRejectedException when Paystack refused the request
     *         outright (4xx); the caller decides whether that saves as
     *         UNAVAILABLE (vendor save) or is reported back (admin re-run)
     */
    public void apply(VendorVerification row, User vendor, String documentNumber) {
        if (!splitEnabled) {
            row.setProviderResult(VerificationProviderResult.NOT_RUN);
            return;
        }
        ValidateRequest request = new ValidateRequest(
                row.getAccountHolderName(),
                vendor.getAccountNumber(),
                PaystackClient.accountTypeParam(row.getDocumentType(), row.getAccountType()),
                row.getBankCode(),
                "ZA",
                PaystackClient.documentTypeParam(row.getDocumentType()),
                documentNumber);
        try {
            ValidateResult result = paystack.validateAccount(request);
            boolean ok = result.verified() && Boolean.TRUE.equals(result.accountHolderMatch());
            row.setProviderResult(ok ? VerificationProviderResult.OK : VerificationProviderResult.MISMATCH);
            row.setAccountHolderMatch(result.accountHolderMatch());
            row.setAccountOpen(result.accountOpen());
            row.setAccountOpen3m(result.accountOpenForMoreThanThreeMonths());
            row.setAcceptsCredits(result.accountAcceptsCredits());
            row.setVerificationMessage(trim(result.message()));
            log.info("Vendor {} account validation: {} (holderMatch={}, open={}, document={})",
                    vendor.getId(), row.getProviderResult(), result.accountHolderMatch(),
                    result.accountOpen(), documentNumber == null ? "absent" : "supplied");
        } catch (PaystackUnavailableException e) {
            row.setProviderResult(VerificationProviderResult.UNAVAILABLE);
            row.setVerificationMessage("Provider unavailable; re-run from the admin page");
            log.warn("Vendor {} account validation unavailable: {}", vendor.getId(), e.getMessage());
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
