package com.marketplace.api.entity;

/**
 * The identity document a vendor nominates for bank account verification
 * (paystack-subaccounts.md §4.1). Lockstep with
 * vendor_verifications_document_type_check in V38: add here, add there.
 */
public enum DocumentType {
    ID_NUMBER,
    PASSPORT,
    CIPC
}
