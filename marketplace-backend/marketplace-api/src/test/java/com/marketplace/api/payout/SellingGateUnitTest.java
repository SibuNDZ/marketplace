package com.marketplace.api.payout;

import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The predicate on its own, no container: with the flag OFF every vendor
 * is sellable whatever their state (the unchanged behaviour PayoutTermsGateTest
 * cannot show, because it flips the flag on for its whole context), and
 * with the flag ON each of the three conditions is individually required.
 */
class SellingGateUnitTest {

    private static final PayoutTerms TERMS = new PayoutTerms(new BigDecimal("0.10"), 7, 3);

    private static User vendor(boolean terms, boolean banking, boolean approved) {
        User u = new User();
        if (terms) u.setPayoutTermsVersion(3);
        if (banking) {
            u.setAccountHolderName("Holder");
            u.setBankName("Nedbank");
            u.setAccountNumber("1122336789");
            u.setBranchCode("198765");
            u.setBankCode("198765");
            u.setAccountType(BankAccountType.CHEQUE);
        }
        if (approved) u.setVendorApprovedAt(LocalDateTime.now());
        return u;
    }

    @Test
    @DisplayName("gate off: unbanked, unaccepted and unapproved vendors are all sellable")
    void gateOffSellsEverything() {
        SellingGate off = new SellingGate(false, false, TERMS);
        assertThat(off.sellable(vendor(false, false, false))).isTrue();
        assertThat(off.sellable(vendor(true, true, false))).isTrue();
        assertThat(off.sellable(vendor(true, false, true))).isTrue();
        assertThat(off.sellable(vendor(false, true, true))).isTrue();
        assertThat(off.sellable(vendor(true, true, true))).isTrue();
    }

    @Test
    @DisplayName("gate on: terms, complete banking AND approval are each required")
    void gateOnNeedsAllThree() {
        SellingGate on = new SellingGate(true, false, TERMS);
        assertThat(on.sellable(vendor(true, true, true))).isTrue();
        assertThat(on.sellable(vendor(false, true, true))).as("no terms").isFalse();
        assertThat(on.sellable(vendor(true, false, true))).as("no banking").isFalse();
        assertThat(on.sellable(vendor(true, true, false))).as("not approved").isFalse();
    }

    @Test
    @DisplayName("gate on: banking without a bank code is not complete")
    void bankCodeIsPartOfComplete() {
        SellingGate on = new SellingGate(true, false, TERMS);
        User u = vendor(true, true, true);
        u.setBankCode(null);
        assertThat(on.sellable(u)).isFalse();
    }

    @Test
    @DisplayName("the subaccount code is required only while the split switch is on")
    void subaccountCodeFollowsTheSplitSwitch() {
        User approvedNoCode = vendor(true, true, true);
        assertThat(new SellingGate(true, false, TERMS).sellable(approvedNoCode)).as("split off").isTrue();
        assertThat(new SellingGate(true, true, TERMS).sellable(approvedNoCode)).as("split on, no code").isFalse();
        approvedNoCode.setPaystackSubaccountCode("ACCT_x");
        assertThat(new SellingGate(true, true, TERMS).sellable(approvedNoCode)).as("split on, code").isTrue();
        assertThat(new SellingGate(false, true, TERMS).sellable(vendor(false, false, false))).as("gate off").isTrue();
    }
}
