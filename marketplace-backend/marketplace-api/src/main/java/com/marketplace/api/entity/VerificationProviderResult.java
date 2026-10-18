package com.marketplace.api.entity;

/**
 * What the payment provider's bank-account validation said about one
 * verification attempt. Lockstep with
 * vendor_verifications_provider_result_check in V38.
 *
 * NOT_RUN is the state slice 1 writes: the validate call is not built yet,
 * so a row records what the vendor submitted and nothing about the account.
 * An admin may approve a NOT_RUN row on their own review; MISMATCH and
 * UNAVAILABLE may not be approved at all (there is no "approve anyway").
 */
public enum VerificationProviderResult {
    OK,
    MISMATCH,
    UNAVAILABLE,
    NOT_RUN
}
