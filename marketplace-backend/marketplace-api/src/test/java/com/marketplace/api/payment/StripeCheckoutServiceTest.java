package com.marketplace.api.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit test for StripeCheckoutService.toCents — no Spring context needed.
 * Verifies that money conversion is exact and loud about sub-cent precision.
 */
class StripeCheckoutServiceTest {

    @Test
    void toCents_convertsCorrectly() {
        assertThat(StripeCheckoutService.toCents(new BigDecimal("199.99"))).isEqualTo(19999L);
    }

    @Test
    void toCents_subCentPrecision_throwsArithmeticException() {
        assertThatThrownBy(() -> StripeCheckoutService.toCents(new BigDecimal("0.005")))
                .isInstanceOf(ArithmeticException.class);
    }
}
