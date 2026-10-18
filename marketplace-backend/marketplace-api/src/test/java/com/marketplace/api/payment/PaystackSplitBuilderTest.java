package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketplace.api.entity.User;
import com.marketplace.api.payment.PaystackSplitBuilder.SplitArithmeticException;
import com.marketplace.api.payout.VendorShareCalculator.VendorShare;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The split object on its own: shape, exclusions, and the refusal to over-split. */
class PaystackSplitBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static VendorShare share(long vendorId, String code, String net) {
        User v = new User();
        v.setId(vendorId);
        v.setPaystackSubaccountCode(code);
        return new VendorShare(v, new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0.10"),
                new BigDecimal("0"), new BigDecimal(net));
    }

    @Test
    @DisplayName("flat, bearer account, one entry per subaccounted vendor in cents, spec reference")
    void shape() {
        ObjectNode split = PaystackSplitBuilder.build(mapper, 77L, 20_000,
                List.of(share(1, "ACCT_a", "85.00"), share(2, "ACCT_b", "12.00")));
        assertThat(split.get("type").asText()).isEqualTo("flat");
        assertThat(split.get("bearer_type").asText()).isEqualTo("account");
        assertThat(split.get("reference").asText()).isEqualTo("ERY-77-SPLIT");
        assertThat(split.get("subaccounts")).hasSize(2);
        assertThat(split.get("subaccounts").get(0).get("subaccount").asText()).isEqualTo("ACCT_a");
        assertThat(split.get("subaccounts").get(0).get("share").asLong()).isEqualTo(8500);
        assertThat(split.get("subaccounts").get(1).get("share").asLong()).isEqualTo(1200);
    }

    @Test
    @DisplayName("a vendor without a subaccount code is left out; with none at all there is no split")
    void exclusions() {
        ObjectNode split = PaystackSplitBuilder.build(mapper, 1L, 20_000,
                List.of(share(1, "ACCT_a", "85.00"), share(2, null, "50.00"), share(3, "ACCT_c", "0.00")));
        assertThat(split.get("subaccounts")).hasSize(1);
        assertThat(split.get("subaccounts").get(0).get("subaccount").asText()).isEqualTo("ACCT_a");

        assertThat(PaystackSplitBuilder.build(mapper, 1L, 20_000,
                List.of(share(1, null, "85.00"), share(2, "", "50.00")))).isNull();
    }

    @Test
    @DisplayName("one cent over the charge is refused, never trimmed (ticket §H item 8)")
    void oneCentOverIsRefused() {
        assertThatThrownBy(() -> PaystackSplitBuilder.build(mapper, 9L, 9_999,
                List.of(share(1, "ACCT_a", "60.00"), share(2, "ACCT_b", "40.00"))))
                .isInstanceOf(SplitArithmeticException.class)
                .hasMessageContaining("10000 cents")
                .hasMessageContaining("9999 cents");
        // Exactly the charge is fine.
        assertThat(PaystackSplitBuilder.build(mapper, 9L, 10_000,
                List.of(share(1, "ACCT_a", "60.00"), share(2, "ACCT_b", "40.00")))).isNotNull();
    }
}
