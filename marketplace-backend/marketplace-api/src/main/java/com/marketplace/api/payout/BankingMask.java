package com.marketplace.api.payout;

import com.marketplace.api.dto.PayoutDtos.MaskedBanking;
import com.marketplace.api.entity.User;

/**
 * THE banking-masking rule — one method, every surface (the shippingFor
 * pattern). Admin payout views and vendor self-service both call this; the
 * full account number exists in exactly one output, the bank CSV, which
 * does not pass through here.
 */
public final class BankingMask {

    private BankingMask() {}

    public static MaskedBanking of(User vendor) {
        String acc = vendor.getAccountNumber();
        String last4 = acc == null || acc.length() < 4
                ? null
                : "···" + acc.substring(acc.length() - 4);
        return new MaskedBanking(vendor.getBankName(), last4, vendor.hasCompleteBankingDetails());
    }
}
