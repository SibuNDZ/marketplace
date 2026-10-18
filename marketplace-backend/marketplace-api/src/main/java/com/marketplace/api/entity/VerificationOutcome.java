package com.marketplace.api.entity;

/**
 * The admin's decision on one verification attempt. Lockstep with
 * vendor_verifications_outcome_check in V38. PENDING until an admin acts;
 * the decision is written once and never reopened (a changed bank account
 * produces a NEW row, not a reset).
 */
public enum VerificationOutcome {
    PENDING,
    APPROVED,
    REJECTED
}
