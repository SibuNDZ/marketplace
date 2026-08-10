package com.marketplace.api.payment;

import java.math.BigDecimal;

/**
 * Rand (BigDecimal) to minor units (cents), shared by every provider that
 * charges in cents. Extracted from StripeCheckoutService when Yoco arrived —
 * two copies of a money conversion is one too many.
 *
 * longValueExact() THROWS on sub-cent precision rather than silently rounding
 * someone's money. If it ever throws, the bug is upstream in price data, and
 * loud is correct.
 */
final class Money {

    static long toCents(BigDecimal rand) {
        return rand.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    private Money() {}
}
