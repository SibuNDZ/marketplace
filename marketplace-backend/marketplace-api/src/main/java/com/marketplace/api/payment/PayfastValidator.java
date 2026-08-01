package com.marketplace.api.payment;

/**
 * Server-confirmation seam: PayFast's fourth prescribed ITN check is to POST
 * the received parameters back to their /eng/query/validate endpoint and
 * require the literal body "VALID". An interface so PayfastItnTest can stub
 * the network call the way AI and storage tests stub their providers.
 */
public interface PayfastValidator {

    /** @param paramString the received params, urlencoded, WITHOUT the signature field. */
    boolean confirms(String paramString);
}
